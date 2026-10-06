"""Local Twitch/HLS fixture for the debug-only VaftFixtureReceiver.

Run with Python and ffmpeg on PATH. Media is generated in a temporary directory.
Control it with /control?primary_vaft=true, /control?primary_vaft=false,
/control?backup_vaft=true, /control?fail=embed, or /control?delay=3.
Only requests for the reserved vaft_fixture channel are redirected by the app.
"""

import argparse
import base64
import datetime
import json
import pathlib
import re
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


CHANNEL = "vaft_fixture"
HOST = "https://vaft-fixture.invalid"
START = time.time() - 60
STATE = {"primary_vaft": False, "backup_vaft": False, "vaft_attributes_only": False, "prefetch": 0, "fail": "", "delay": 0.0, "segment_delay": 0.0, "backup_date_offset": 0.0, "range_age_seconds": None, "ladder": False, "primary_max": 720, "backup_max": 360, "unavailable": False, "real_backup": False}
LOCK = threading.Lock()
ROOT = pathlib.Path(tempfile.mkdtemp(prefix="xtra-vaft-fixture-"))
DEVICE_ID = uuid.uuid4().hex
VAFT_RANGE_TEMPLATE = None


def captured_vaft_range(first, last, state):
    if VAFT_RANGE_TEMPLATE is None:
        return None
    original = re.search(r'START-DATE="([^"]+)"', VAFT_RANGE_TEMPLATE)
    original_time = datetime.datetime.fromisoformat(original[1].replace("Z", "+00:00"))
    range_start = START + first * 2
    if state["range_age_seconds"] is not None:
        range_start = START + last * 2 - state["range_age_seconds"]
    shifted_time = datetime.datetime.fromtimestamp(range_start, datetime.timezone.utc)
    shift = shifted_time - original_time

    def shift_date(match):
        value = datetime.datetime.fromisoformat(match[2].replace("Z", "+00:00")) + shift
        return f'{match[1]}="{value.isoformat().replace("+00:00", "Z")}"'

    line = re.sub(r'(START-DATE|END-DATE)="([^"]+)"', shift_date, VAFT_RANGE_TEMPLATE)
    # Each fixture window has a distinct identity, so a previous snapshot cannot
    # retain a range with different dates under the same identity.
    return re.sub(r'(^#EXT-X-DATERANGE:|,)ID="[^"]+"',
                  lambda match: f'{match[1]}ID="fixture-vaft-{first}"', line)


def real_backup_master(player_type):
    # Optional wire verification: only the primary is simulated. Backup media
    # comes directly from Twitch, without forwarding account credentials.
    variables = {"login": "ironmouse", "platform": "android" if player_type == "autoplay" else "web", "playerType": player_type}
    body = json.dumps({"query": 'query StreamPlaybackAccessToken($login:String!,$platform:String!,$playerType:String!){streamPlaybackAccessToken(channelName:$login,params:{platform:$platform,playerType:$playerType}){signature value}}', "variables": variables}).encode()
    request = urllib.request.Request("https://gql.twitch.tv/gql", data=body, headers={
        "Client-ID": "kimne78kx3ncx6brgo4mv6wki5h1ko", "X-Device-Id": DEVICE_ID, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=5) as response:
        token = json.load(response)["data"]["streamPlaybackAccessToken"]
    query = urllib.parse.urlencode({"allow_source": "true", "allow_audio_only": "true", "sig": token["signature"],
                                   "token": token["value"], "supported_codecs": "av1,h265,h264", "platform": "web"})
    with urllib.request.urlopen("https://usher.ttvnw.net/api/v2/channel/hls/ironmouse.m3u8?" + query, timeout=5) as response:
        return response.read()


def timestamp(seconds):
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def generate_media():
    for lane, color in [("primary", "0x194d80"), ("backup", "0x806619"), ("replay", "0x4d1980")]:
        folder = ROOT / lane
        folder.mkdir()
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
            f"color=c={color}:s=320x180:r=60", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "600", "-vf", f"drawtext=text='{lane.upper()} %{{pts\\:hms}}':fontcolor=white:fontsize=22:x=12:y=70",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "120",
            "-sc_threshold", "0", "-c:a", "aac", "-b:a", "48k", "-f", "segment",
            "-segment_time", "2", "-segment_format", "mpegts", str(folder / "%04d.ts"),
        ], check=True)


def generate_ladder():
    # Real decoded dimensions, different codec profiles, and IVS labels without
    # VIDEO groups reproduce Twitch's current multivariant shape.
    for lane, color, heights in [("primary", "0x194d80", [1080, 720, 360]),
                                  ("backup", "0x806619", [360, 160]),
                                  ("replay", "0x4d1980", [720, 360])]:
        for height in heights:
            folder = ROOT / f"{lane}{height}"
            if folder.exists():
                continue
            folder.mkdir()
            width = {1080: 1920, 720: 1280, 360: 640, 160: 284}[height]
            subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                f"color=c={color}:s={width}x{height}:r=60", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000", "-t", "20", "-vf",
                f"drawtext=text='{lane.upper()} {height}p60 %{{pts\\:hms}}':fontcolor=white:fontsize={max(18,height//12)}:x=12:y={height//2}",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "120",
                "-sc_threshold", "0", "-c:a", "aac", "-b:a", "48k", "-f", "segment",
                "-segment_time", "2", "-segment_format", "mpegts", str(folder / "%04d.ts"),
            ], check=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def respond(self, body, content_type="application/vnd.apple.mpegurl", status=200, headers=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        variables = data.get("variables", {})
        match = re.search(r"query\s+(\w+)", data.get("query", ""))
        operation = data.get("operationName") or (match.group(1) if match else "")
        if operation in ("", "null"):
            query = data.get("query", "")
            operation = next((name for field, name in [
                ("searchStreams(", "SearchStreams"), ("searchUsers(", "SearchChannels"),
                ("users(", "UsersStream"), ("videos(", "UserVideos"),
                ("streamPlaybackAccessToken(", "PlaybackAccessToken"),
                ("videoPlaybackAccessToken(", "PlaybackAccessToken"),
                ("user(", "UserChannelPage"),
            ] if field in query), "")
        print(f"fixture operation={operation!r} variable_names={list(variables)}", flush=True)
        created = timestamp(START)
        duration = int(time.time() - START)
        if "PlaybackAccessToken" in operation or "PlaybackAccessToken" in data.get("query", ""):
            player_type = variables.get("playerType", "site")
            token = {"signature": player_type, "value": json.dumps({"player_type": player_type})}
            result = {"data": {"streamPlaybackAccessToken": token, "videoPlaybackAccessToken": token}}
        elif operation == "UsersStream":
            result = {"data": {"users": [{"__typename": "User", "id": CHANNEL, "login": CHANNEL,
                "displayName": "VAFT fixture", "profileImageURL": None,
                "stream": {"__typename": "Stream", "id": "fixture-live", "createdAt": created,
                    "viewersCount": 1, "previewImageURL": None, "game": None, "freeformTags": [],
                    "broadcaster": {"__typename": "User", "broadcastSettings": {
                        "__typename": "BroadcastSettings", "title": "Controlled VAFT and replay verification"}}}}]}}
        elif operation == "UserChannelPage":
            result = {"data": {"user": {"__typename": "User", "id": CHANNEL, "login": CHANNEL,
                "displayName": "VAFT fixture", "profileImageURL": None, "bannerImageURL": None,
                "createdAt": created, "lastBroadcast": None,
                "followers": {"__typename": "FollowerConnection", "totalCount": 0},
                "roles": {"__typename": "UserRoles", "isAffiliate": False, "isPartner": False, "isStaff": False},
                "stream": {"__typename": "Stream", "id": "fixture-live", "createdAt": created,
                    "viewersCount": 1, "previewImageURL": None, "game": None,
                    "title": "Controlled VAFT and replay verification"}}}}
        elif operation == "UserVideos":
            result = {"data": {"user": {"__typename": "User", "login": CHANNEL, "displayName": "VAFT fixture",
                "profileImageURL": None, "videos": {"__typename": "VideoConnection", "edges": [{
                    "__typename": "VideoEdge", "cursor": "fixture", "node": {"__typename": "Video",
                    "id": "999999999999", "createdAt": created, "lengthSeconds": duration,
                    "broadcastType": "ARCHIVE", "title": "Fixture replay", "viewCount": 1,
                    "game": None, "animatedPreviewURL": None, "previewThumbnailURL": None}}],
                    "pageInfo": {"__typename": "PageInfo", "hasNextPage": False}}}}}
        elif operation == "SearchChannels":
            result = {"data": {"searchUsers": {"__typename": "SearchUsersConnection", "edges": [{
                "__typename": "SearchUsersEdge", "cursor": "fixture", "node": {"__typename": "User",
                "id": CHANNEL, "login": CHANNEL, "displayName": "VAFT fixture", "profileImageURL": None,
                "followers": {"__typename": "FollowerConnection", "totalCount": 0},
                "stream": {"__typename": "Stream", "viewersCount": 1}}}],
                "pageInfo": {"__typename": "PageInfo", "hasNextPage": False}}}}
        elif operation == "SearchResultsPage_SearchResults":
            result = {"data": {"searchFor": {"channels": {"edges": [{"item": {
                "id": CHANNEL, "login": CHANNEL, "displayName": "VAFT fixture", "profileImageURL": None,
                "followers": {"totalCount": 0}, "stream": {"viewersCount": 1}}}], "cursor": None}}}}
        elif operation == "SearchStreams":
            result = {"data": {"searchStreams": {"__typename": "SearchStreamsConnection", "edges": [{
                "__typename": "SearchStreamsEdge", "cursor": "fixture", "node": {"__typename": "Stream",
                "id": "fixture-live", "createdAt": created, "previewImageURL": None, "viewersCount": 1,
                "game": None, "freeformTags": [], "broadcaster": {"__typename": "User", "id": CHANNEL,
                "login": CHANNEL, "displayName": "VAFT fixture", "profileImageURL": None,
                "broadcastSettings": {"__typename": "BroadcastSettings", "title": "Controlled VAFT and replay"}}}}],
                "pageInfo": {"__typename": "PageInfo", "hasNextPage": False}}}}
        else:
            result = {"data": {"user": None}}
        self.respond(json.dumps(result), "application/json")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        if parsed.path == "/control":
            with LOCK:
                for key, values in query.items():
                    if key in ("primary_vaft", "backup_vaft", "vaft_attributes_only", "ladder", "unavailable", "real_backup"):
                        STATE[key] = values[0].lower() == "true"
                    elif key in ("delay", "segment_delay", "backup_date_offset"):
                        STATE[key] = float(values[0])
                    elif key == "range_age_seconds":
                        STATE[key] = max(0.0, float(values[0]))
                    elif key == "prefetch":
                        STATE[key] = max(0, min(2, int(values[0])))
                    elif key in ("primary_max", "backup_max"):
                        STATE[key] = int(values[0])
                    elif key == "fail":
                        STATE[key] = values[0]
                state = dict(STATE)
            self.respond(json.dumps(state), "application/json")
            return
        with LOCK:
            state = dict(STATE)
        player_type = query.get("sig", ["site"])[0] if parsed.path == "/master" else None
        if parsed.path in ("/master", "/vod/master"):
            if player_type == state["fail"]:
                self.respond("fixture failure", "text/plain", 503)
                return
            if player_type and player_type != "site":
                time.sleep(state["delay"])
                if state["real_backup"]:
                    try:
                        self.respond(real_backup_master(player_type))
                    except Exception as error:
                        print(f"real backup unavailable: {type(error).__name__}", flush=True)
                        self.respond("real backup unavailable", "text/plain", 503)
                    return
            route = "vod" if parsed.path == "/vod/master" else f"stream/{player_type}"
            if state["ladder"]:
                if route == "vod":
                    heights = [720, 360]
                elif player_type == "site":
                    heights = [1080, 720, 360] if state["primary_max"] == 1080 else [720, 360]
                else:
                    heights = [height for height in [360, 160] if height <= state["backup_max"]]
                lines = ["#EXTM3U"]
                if state["unavailable"] and player_type != "site" and route != "vod":
                    unavailable = [{"IVS_NAME": "160p60", "STABLE-VARIANT-ID": "160p60", "RESOLUTION": "284x160", "BANDWIDTH": 480000,
                                    "CODECS": "avc1.42c00d,mp4a.40.2", "FRAME-RATE": 60, "FILTER_REASONS": ["FR_WAITING_TRANSCODE"]}]
                    encoded = base64.b64encode(json.dumps(unavailable).encode()).decode()
                    lines.append(f'#EXT-X-SESSION-DATA:DATA-ID="com.amazon.ivs.unavailable-media",VALUE="{encoded}"')
                    heights = [360]
                for height in heights:
                    width = {1080: 1920, 720: 1280, 360: 640, 160: 284}[height]
                    codec = {1080: "avc1.42c02a", 720: "avc1.42c020", 360: "avc1.42c01f", 160: "avc1.42c00d"}[height]
                    lines += [f'#EXT-X-STREAM-INF:BANDWIDTH={height*3000},RESOLUTION={width}x{height},CODECS="{codec},mp4a.40.2",FRAME-RATE=60,IVS-NAME="{height}p60",STABLE-VARIANT-ID="{height}p60"',
                              f"{HOST}/{route}/{height}p60/index-live.m3u8"]
                self.respond("\n".join(lines) + "\n")
                return
            self.respond(f'#EXTM3U\n#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="source",NAME="Source",AUTOSELECT=YES,DEFAULT=YES,URI="{HOST}/{route}/video.m3u8"\n'
                f'#EXT-X-STREAM-INF:BANDWIDTH=180000,CODECS="avc1.42c00c,mp4a.40.2",RESOLUTION=320x180,FRAME-RATE=60,VIDEO="source"\n'
                f'{HOST}/{route}/video.m3u8\n')
            return
        parts = parsed.path.strip("/").split("/")
        if parts[-1].endswith(".m3u8"):
            replay = parts[0] == "vod"
            primary = not replay and parts[1] == "site"
            lane = "replay" if replay else "primary" if primary else "backup"
            if parts[-1] != "video.m3u8":
                lane += "160" if parts[-2] == "chunked" else parts[-2].removesuffix("p60")
            segment_count = len(list((ROOT / lane).glob("*.ts")))
            if segment_count == 0:
                self.respond("unknown rendition", "text/plain", 404)
                return
            last = int((time.time() - START) / 2)
            first = 0 if replay else max(0, last - 9)
            vaft_required = not replay and state["primary_vaft" if primary else "backup_vaft"]
            if state["unavailable"] and "chunked" in parts:
                vaft_required = True
            lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:2", f"#EXT-X-MEDIA-SEQUENCE:{first}"]
            lines.append(f"#EXT-X-DISCONTINUITY-SEQUENCE:{max(0, (first - 1) // segment_count)}")
            if replay:
                lines.append("#EXT-X-PLAYLIST-TYPE:EVENT")
            else:
                # Normal Twitch live playlists carry this open-ended trigger too.
                # It must not suppress video or disqualify a clean backup.
                attributes = ',X-TV-TWITCH-AD-ID="fixture-vaft"' if vaft_required and state["vaft_attributes_only"] else ""
                lines.append(f'#EXT-X-DATERANGE:ID="trigger-{first}",CLASS="twitch-trigger",START-DATE="{timestamp(START + first * 2)}",END-ON-NEXT=YES,X-TV-TWITCH-TRIGGER-URL="https://vaft-fixture.invalid/trigger"{attributes}')
                if vaft_required and VAFT_RANGE_TEMPLATE is not None:
                    lines.append(captured_vaft_range(first, last, state))
                elif vaft_required and state["range_age_seconds"] is not None:
                    range_start = timestamp(START + last * 2 - state["range_age_seconds"])
                    lines.append(f'#EXT-X-DATERANGE:ID="vaft-fixture-range-{first}",CLASS="twitch-stitched-vaft",START-DATE="{range_start}",PLANNED-DURATION=60')
            for i in range(first, last + 1):
                if i > 0 and i % segment_count == 0:
                    lines.append("#EXT-X-DISCONTINUITY")
                date_offset = state["backup_date_offset"] if not primary and not replay else 0.0
                lines += [f"#EXT-X-PROGRAM-DATE-TIME:{timestamp(START + i * 2 + date_offset)}",
                    f"#EXTINF:2.0,{'Amazon' if vaft_required and not state['vaft_attributes_only'] and VAFT_RANGE_TEMPLATE is None else 'live'}", f"{HOST}/segments/{lane}/{i:08d}.ts"]
            if not replay:
                for i in range(last + 1, last + 1 + state["prefetch"]):
                    lines.append(f"#EXT-X-TWITCH-PREFETCH:{HOST}/segments/{lane}/{i:08d}.ts")
            self.respond("\n".join(lines) + "\n")
            return
        if len(parts) == 3 and parts[0] == "segments":
            if parts[1].startswith("backup"):
                time.sleep(state["segment_delay"])
            lane_dir = ROOT / parts[1]
            segment_count = len(list(lane_dir.glob("*.ts")))
            if not segment_count or not re.fullmatch(r"\d+\.ts", parts[2]):
                self.respond("unknown segment", "text/plain", 404)
                return
            path = (lane_dir / f"{int(parts[2][:-3]) % segment_count:04d}.ts").resolve()
            if path.is_relative_to(ROOT) and path.is_file():
                body = path.read_bytes()
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
                if match:
                    first = int(match[1])
                    last = min(len(body) - 1, int(match[2]) if match[2] else len(body) - 1)
                    if first >= len(body):
                        self.respond(b"", "video/mp2t", 416, {"Content-Range": f"bytes */{len(body)}"})
                    else:
                        self.respond(body[first:last + 1], "video/mp2t", 206,
                            {"Content-Range": f"bytes {first}-{last}/{len(body)}"})
                else:
                    self.respond(body, "video/mp2t")
                return
        self.respond("not found", "text/plain", 404)

    def log_message(self, format, *args):
        # Paths contain only fixture parameters. Never log request headers.
        print(format % args, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--media-dir", type=pathlib.Path)
    parser.add_argument("--start-time", type=float)
    parser.add_argument("--generate-ladder", action="store_true")
    parser.add_argument("--vaft-range-template", type=pathlib.Path,
                        help="Private captured playlist JSON whose VAFT range is replayed locally")
    args = parser.parse_args()
    if args.vaft_range_template:
        record = json.loads(args.vaft_range_template.read_text(encoding="utf-8"))
        VAFT_RANGE_TEMPLATE = next(line for line in record["playlist"].splitlines()
                                   if line.startswith("#EXT-X-DATERANGE:") and 'CLASS="twitch-maf-ad"' in line)
        if not re.search(r'START-DATE="[^"]+"', VAFT_RANGE_TEMPLATE):
            parser.error("Captured range has no start date")
        # Fixture media stays local even if a captured range names a remote asset.
        VAFT_RANGE_TEMPLATE = re.sub(r'X-ASSET-URI="[^"]*"', 'X-ASSET-URI=""', VAFT_RANGE_TEMPLATE)
    if args.media_dir:
        ROOT = args.media_dir.resolve()
    else:
        generate_media()
    if args.generate_ladder:
        generate_ladder()
    START = args.start_time if args.start_time is not None else time.time() - 60
    print(f"VAFT fixture ready on port {args.port}; media in {ROOT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
