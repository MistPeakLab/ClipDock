"""把 src.html 的每一段渲染成 PNG（帖子里用 jsDelivr 链接引用这些图）。

用法：python docs/post/build.py [段名 ...]   不写段名就全部重出
需要：Microsoft Edge、Pillow
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src.html")
SECTIONS = ["why", "update", "open", "multi", "pick", "transform", "notes"]
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
SCALE = 2


def render(name):
    raw = os.path.join(tempfile.gettempdir(), f"clipdock-post-{name}.png")
    if os.path.exists(raw):
        os.remove(raw)
    url = "file:///" + SRC.replace("\\", "/") + "#" + name
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    f"--force-device-scale-factor={SCALE}", "--window-size=860,1600",
                    "--virtual-time-budget=4000", f"--screenshot={raw}", url],
                   check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    im = Image.open(raw).convert("RGB")
    bbox = ImageChops.difference(im, Image.new("RGB", im.size, "white")).getbbox()
    im = im.crop((0, 0, im.width, min(im.height, bbox[3] + 22 * SCALE)))
    out = os.path.join(HERE, f"{name}.png")
    im.quantize(colors=256, method=Image.Quantize.MEDIANCUT).save(out, optimize=True)
    os.remove(raw)
    print(name, im.size, os.path.getsize(out) // 1024, "KB")


if __name__ == "__main__":
    for n in sys.argv[1:] or SECTIONS:
        render(n)
