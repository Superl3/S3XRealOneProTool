"""Train a MediaPipe Gesture Recognizer on the images the app's dataset recorder saved.

NOT RUN (2026-09-29): written against the mediapipe-model-maker API as documented; neither the
package's Python/OS support nor a training run was checked. The app does not load the exported
model yet — it only loads hand_landmarker.task — so the output is for evaluation until it does.

usage:
  adb pull /sdcard/Android/data/io.github.xrealeyetools/files/dataset ./dataset
  pip install mediapipe-model-maker
  python train_gesture_recognizer.py ./dataset [--out exported_model] [--epochs 10] ...

The dataset is one folder per label (Settings → Dataset label in the app: none, closed_fist,
open_palm, pinch, victory, thumb_up). Model Maker needs a `none` folder (hand visible, no
gesture) and at least one gesture. Images where MediaPipe finds no hand are dropped while the
dataset loads, so the counts printed here are an upper bound. Split: 80 % train, 10 % validation,
10 % test. The result is <out>/gesture_recognizer.task.
"""
import argparse
import os
import sys

IMAGE_EXT = (".jpg", ".jpeg", ".png")


def count_images(root):
    counts = {}
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if os.path.isdir(path):
            counts[name] = sum(1 for f in os.listdir(path) if f.lower().endswith(IMAGE_EXT))
    return counts


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dataset", help="folder with one sub-folder per label")
    ap.add_argument("--out", default="exported_model", help="export directory")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--batch-size", type=int, default=2)
    ap.add_argument("--learning-rate", type=float, default=0.001)
    ap.add_argument("--dropout", type=float, default=0.05)
    ap.add_argument("--min-detection-confidence", type=float, default=0.7,
                    help="hand detection threshold while loading the images")
    args = ap.parse_args()

    counts = count_images(args.dataset)
    for label, n in counts.items():
        print(f"{label:>12}: {n} images")
    if counts.get("none", 0) == 0:
        sys.exit("No 'none' images: record some frames with the label 'none' (hand visible, no gesture).")
    if not any(n > 0 for label, n in counts.items() if label != "none"):
        sys.exit("Need images for at least one gesture besides 'none'.")

    from mediapipe_model_maker import gesture_recognizer as gr

    data = gr.Dataset.from_folder(
        dirname=args.dataset,
        hparams=gr.HandDataPreprocessingParams(min_detection_confidence=args.min_detection_confidence),
    )
    train, rest = data.split(0.8)
    validation, test = rest.split(0.5)
    print(f"train {train.size}, validation {validation.size}, test {test.size} (after hand detection)")

    options = gr.GestureRecognizerOptions(
        model_options=gr.ModelOptions(dropout_rate=args.dropout),
        hparams=gr.HParams(export_dir=args.out, epochs=args.epochs,
                           batch_size=args.batch_size, learning_rate=args.learning_rate),
    )
    model = gr.GestureRecognizer.create(train_data=train, validation_data=validation, options=options)
    loss, accuracy = model.evaluate(test, batch_size=1)
    print(f"test loss {loss:.4f}, accuracy {accuracy:.4f}")
    model.export_model()
    print(f"wrote {os.path.join(args.out, 'gesture_recognizer.task')}")


if __name__ == "__main__":
    main()
