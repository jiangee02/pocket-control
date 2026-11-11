import os
import zipfile

src = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "relay-server")
dst = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "deliverables", "随手控-中继服务器-relay.zip")
if os.path.exists(dst):
    os.remove(dst)
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        files.sort()
        for f in files:
            p = os.path.join(root, f)
            arc = os.path.relpath(p, src).replace(os.sep, "/")
            z.write(p, arc)
print("entries:", len(zipfile.ZipFile(dst).namelist()))
