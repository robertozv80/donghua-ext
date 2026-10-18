import json
import pathlib

s = pathlib.Path("tools/ac_flight.txt").read_text(encoding="utf-8")
i = s.find('"slug":"renegade-immortal"')
print("slug pos:", i)
start = s.rfind("{", 0, i)
print("obj head:", s[start : start + 120].replace("\n", " "))

depth = 0
end = -1
in_str = False
esc = False
k = start
while k < len(s):
    c = s[k]
    if esc:
        esc = False
    elif c == "\\" and in_str:
        esc = True
    elif c == '"':
        in_str = not in_str
    elif not in_str and c == "{":
        depth += 1
    elif not in_str and c == "}":
        depth -= 1
        if depth == 0:
            end = k + 1
            break
    k += 1

obj_text = s[start:end]
print("len obj:", len(obj_text))
try:
    obj = json.loads(obj_text)
    for key, value in obj.items():
        print(f"  {key}: {str(value)[:100]}")
except Exception as e:
    print("JSON ERR:", e)
    print(obj_text[:600])
