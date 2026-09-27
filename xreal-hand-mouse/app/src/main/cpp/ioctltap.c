#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <linux/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define TAG "IOCTLTAP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static int (*real_ioctl)(int, unsigned long, ...) = NULL;

__attribute__((constructor))
static void tap_init(void) { LOGI("IOCTLTAP active (LD_PRELOAD)"); }

// Teto de dump. Subiu de 64 -> 1024 porque as respostas IN do protocolo XREAL tem 1024 B e o
// teto antigo escondia ~94% delas (achado da revisao final whole-branch).
#define DUMP_MAX 1024
// Emitimos em pedacos: o __android_log_print trunca perto de 4000 chars, e 1024 B em hex dariam
// ~3072 chars + prefixo — perto demais do limite pra truncar SILENCIOSAMENTE e corromper a
// captura sem aviso. 256 B/linha = 768 chars, com folga larga.
#define DUMP_CHUNK 256

static void hexdump(const char *label, const unsigned char *p, int len) {
    if (!p || len <= 0) { LOGI("%s <none>", label); return; }
    int n = len > DUMP_MAX ? DUMP_MAX : len;
    char buf[DUMP_CHUNK * 3 + 4];
    // off= torna as linhas reassemblaveis sem ambiguidade, mesmo fora de ordem no logcat.
    for (int off = 0; off < n; off += DUMP_CHUNK) {
        int m = (n - off) > DUMP_CHUNK ? DUMP_CHUNK : (n - off);
        int o = 0;
        for (int i = 0; i < m; i++) o += snprintf(buf + o, sizeof(buf) - o, "%02x ", p[off + i]);
        LOGI("%s len=%d off=%d %s%s", label, len, off, buf, (off + m < len) ? "..." : "");
    }
}

int ioctl(int fd, unsigned long request, ...) {
    va_list ap; va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (!real_ioctl) real_ioctl = dlsym(RTLD_NEXT, "ioctl");
    if (!real_ioctl) {
        static int logged = 0;
        if (!logged) {
            LOGI("ERROR: Failed to resolve real ioctl via dlsym");
            logged = 1;
        }
        errno = ENOSYS;
        return -1;
    }

    if (request == USBDEVFS_CONTROL) {
        struct usbdevfs_ctrltransfer *c = arg;
        LOGI("CONTROL fd=%d bmRequestType=0x%02x bRequest=0x%02x wValue=0x%04x wIndex=0x%04x wLength=%d",
             fd, c->bRequestType, c->bRequest, c->wValue, c->wIndex, c->wLength);
        if ((c->bRequestType & 0x80) == 0) hexdump("  ctrl-OUT", c->data, c->wLength);
        int r = real_ioctl(fd, request, arg);
        int saved_errno = errno;
        if ((c->bRequestType & 0x80) && r >= 0) hexdump("  ctrl-IN", c->data, r);
        LOGI("CONTROL ret=%d", r);
        errno = saved_errno;
        return r;
    }
    if (request == USBDEVFS_BULK) {
        struct usbdevfs_bulktransfer *b = arg;
        LOGI("BULK fd=%d ep=0x%02x len=%u", fd, b->ep, b->len);
        if ((b->ep & 0x80) == 0) hexdump("  bulk-OUT", b->data, b->len);
        int r = real_ioctl(fd, request, arg);
        int saved_errno = errno;
        if ((b->ep & 0x80) && r >= 0) hexdump("  bulk-IN", b->data, r);
        LOGI("BULK ret=%d", r);
        errno = saved_errno;
        return r;
    }
    if (request == USBDEVFS_SUBMITURB) {
        struct usbdevfs_urb *u = arg;
        LOGI("SUBMITURB fd=%d type=%d ep=0x%02x len=%d", fd, u->type, u->endpoint, u->buffer_length);
        if (u->type == USBDEVFS_URB_TYPE_CONTROL) hexdump("  urb-setup+data", u->buffer, u->buffer_length);
        else if ((u->endpoint & 0x80) == 0) hexdump("  urb-OUT", u->buffer, u->buffer_length);
        return real_ioctl(fd, request, arg);
    }
    if (request == USBDEVFS_REAPURB || request == USBDEVFS_REAPURBNDELAY) {
        int r = real_ioctl(fd, request, arg);
        struct usbdevfs_urb **pu = arg;
        if (r == 0 && pu && *pu) {
            struct usbdevfs_urb *u = *pu;
            LOGI("REAPURB type=%d ep=0x%02x actual=%d status=%d", u->type, u->endpoint, u->actual_length, u->status);
            if (u->endpoint & 0x80) hexdump("  urb-IN", (const unsigned char *)u->buffer, u->actual_length);
        }
        return r;
    }
    return real_ioctl(fd, request, arg);  // todos os outros ioctls passam direto
}
