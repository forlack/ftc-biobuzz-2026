#!/usr/bin/env python3
"""Converts auton files from version 2 to version 3, in place (the old file is kept as .v2).

  python3 tools/autos_v2_to_v3.py autos/*.json

Version 2 listed paths separately from the steps, each path with its own start point and
start heading. Version 3 puts each drive in the steps and starts it where the previous one
ended, so this checks that every path really starts there (position and heading) and says
so if one did not: those are the places to look at in the editor after converting.
"""
import json
import math
import re
import shutil
import sys

def close(a, b, tolerance=0.01):
    return abs(a - b) <= tolerance

def same_angle(a, b):
    return close(((a - b + 180) % 360) - 180, 0, 0.5)

def end_tangent(points, reverse):
    """Heading (degrees) the robot faces at the end when it faces where it is going."""
    (x1, y1), (x2, y2) = points[-2], points[-1]
    angle = math.degrees(math.atan2(y2 - y1, x2 - x1))
    return (angle + 180) % 360 if reverse else angle % 360

def convert(route, label):
    notes = []
    paths = {p['name']: p for p in route['paths']}
    at = (route['start']['x'], route['start']['y'])
    heading = route['start']['heading']
    steps = []
    for step in route['steps']:
        if 'follow' not in step:
            steps.append(step)
            continue
        path = paths[step['follow']]
        points = path['points']
        if not (close(points[0][0], at[0]) and close(points[0][1], at[1])):
            notes.append(f"{path['name']} started at {points[0]}, not where the robot was ({at[0]}, {at[1]}): "
                         "it now starts there")
        old = path.get('heading') or {'type': 'tangential'}
        if old['type'] == 'linear':
            if not same_angle(old['start'], heading):
                notes.append(f"{path['name']} turned from {old['start']}° but the robot faced {heading:.0f}°: "
                             "it now turns from where it faces")
            new_heading = {'type': 'keep'} if same_angle(old['end'], heading) else {'type': 'turn', 'degrees': old['end']}
            heading = old['end']
        elif old['type'] == 'constant':
            new_heading = {'type': 'keep'} if same_angle(old['degrees'], heading) else {'type': 'turn', 'degrees': old['degrees']}
            if not same_angle(old['degrees'], heading):
                notes.append(f"{path['name']} snapped to {old['degrees']}°: it now turns while driving")
            heading = old['degrees']
        else:
            reverse = bool(old.get('reverse'))
            new_heading = {'type': 'travel', 'reverse': reverse} if reverse else {'type': 'travel'}
            heading = end_tangent(points, reverse)
        drive = {'name': path['name'],
                 'segments': [{'via': points[1:-1], 'to': points[-1]} if len(points) > 2 else {'to': points[-1]}],
                 'heading': new_heading}
        if path.get('speedPercent'):
            drive['speedPercent'] = path['speedPercent']
        if path.get('events'):
            drive['events'] = path['events']
        steps.append({'drive': drive})
        at = tuple(points[-1])
    result = {'version': 3, 'name': route.get('name', label), 'start': route['start']}
    if 'robot' in route:
        result['robot'] = route['robot']
    result['steps'] = steps
    return result, notes

def pretty(value):
    """Indented JSON, with points like [60, 16] and small objects kept on one line."""
    text = json.dumps(value, indent=2)
    text = re.sub(r'\[\s+(-?[\d.]+),\s+(-?[\d.]+)\s+\]', r'[\1, \2]', text)
    text = re.sub(r'\{\s+("[^{}\[\]]*?)\s+\}', lambda m: '{ ' + re.sub(r',\s+', ', ', m.group(1)) + ' }', text)
    return text

def main(files):
    for name in files:
        with open(name) as f:
            route = json.load(f)
        if route.get('version') != 2:
            print(f'{name}: version {route.get("version")}, left as it is')
            continue
        converted, notes = convert(route, name)
        shutil.copyfile(name, name + '.v2')
        with open(name, 'w') as f:
            f.write(pretty(converted) + '\n')
        print(f'{name}: converted ({sum("drive" in s for s in converted["steps"])} drives, {len(converted["steps"])} steps); old file in {name}.v2')
        for note in notes:
            print(f'  check: {note}')

if __name__ == '__main__':
    main(sys.argv[1:])
