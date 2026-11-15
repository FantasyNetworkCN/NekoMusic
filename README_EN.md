# NekoMusic

![](https://count.getloli.com/get/@:NekoMusic?theme=moebooru)

[English](README_EN.md) | [简体中文](README.md)

An online music platform for Web, Android, and PC. See
[Neko歌姬计划文档/README.md](Neko歌姬计划文档/README.md) for the complete API reference
(Simplified Chinese).

## Docker Deployment (Backend)

The backend Docker configuration lives in `backend/` and listens on port `65535` by
default. The current Compose setup uses `network_mode: host` and `gpus: all`, which is
intended for Linux hosts with the NVIDIA Container Toolkit. On non-Linux systems or hosts
without an NVIDIA GPU, switch to CPU rendering as described below.

First deployment:

```bash
cd backend
cp src/main/resources/config.yml config.yml
# Edit config.yml: only MySQL connection details are required here.
mkdir -p Music releases user_upload
NEKO_WEB_VERSION="$(git describe --tags --always)" docker compose build --pull
docker compose up -d
docker compose logs -f neko-music
```

Check that the service is running:

```bash
curl -fsS http://127.0.0.1:65535/version
```

Audio files are stored at `backend/Music/{id}.*`, with cover art embedded in the audio.
Fingerprint indexes live in `backend/Music/.fingerprints/`. Pending review uploads and
client release packages are stored in `backend/user_upload/` and `backend/releases/`
respectively. Avatars are managed exclusively by NekoAuth.

When upgrading an existing deployment, move audio files and `derived/{id}` quality
directories into `Music/` and `Music/derived/{id}`, and keep `.fingerprints/`. To reuse
data from an existing deployment, set `NEKO_DATA_DIR=/path/to/original/runtime` in
`backend/.env`.

`backend/config.yml` only contains MySQL bootstrap settings. Never commit passwords or
other secrets to Git. Runtime settings are stored in the `system_settings` table of the
primary database and must not be exposed either. After updating the code, run:

```bash
NEKO_WEB_VERSION="$(git describe --tags --always)" docker compose build --pull
docker compose up -d
```

On first startup, the backend creates the `system_settings` table and seeds it once with
factory defaults (or values from an older `config.yml`). After that, all runtime settings,
including ports, Redis, JWT, email, rate limits, network and security, video rendering,
payments, and logging, are managed from the admin **System Settings** page. Changes take
effect immediately, and matching entries in `config.yml` are ignored.

The default configuration uses `video_render.pipeline: cuda_native`. It requires an NVIDIA
GPU, driver, container toolkit, and an FFmpeg build with NVENC/CUDA support. Without a GPU:

- In **Admin → System Settings → Video Rendering**, change `pipeline` to `cpu_legacy` and
  `video_codec` to `libx264`, then restart the backend.
- Remove `gpus: all` from `backend/docker-compose.yaml`.
- On Docker Desktop, replace `network_mode: host` with `ports: ["65535:65535"]`, and change
  the MySQL/Redis hosts to the addresses that are reachable from the container.

## Account System (NekoAuth)

NekoAuth is the single source of truth for accounts, passwords, login sessions, and user
tokens. NekoMusic no longer stores user passwords or user tokens. The `users` table only
keeps local business data:

- `auth_user_id` stores the stable numeric NekoAuth user ID and has a unique index. It is
  the primary account association key.
- `email` is an email snapshot returned by NekoAuth for legacy API responses, admin
  displays, email notifications, and first-time association recovery. It is not a password
  or login credential.
- Email verification is managed by NekoAuth and is not a NekoMusic login gate. NekoMusic
  associates local business data by the NekoAuth `sub`.
- `nickname` is owned by NekoAuth. NekoMusic's MySQL database does not store a nickname
  column. Comments, notifications, playlists, user search, and admin views resolve public
  profile data from NekoAuth in real time to avoid stale copies.
- Avatars are owned by NekoAuth. Uploads, replacements, default avatars, and public reads
  are handled there. NekoMusic only exposes a read-only avatar proxy. `vip_expires_at`
  continues to be maintained locally by NekoMusic.
- Password handling, registration verification codes, password-reset codes, account
  enablement, and user access-token issuance, validation, and revocation are all handled by
  NekoAuth. NekoMusic's Redis no longer stores any `user_token` keys.

Legacy email/password registration, login, verification-code, password-recovery, and
password-change APIs have been removed. Web, Android, and PC must obtain a NekoAuth access
token through the NekoAuth login page or QR-code login. Older clients must be upgraded.

After the first successful login or registration, NekoMusic looks up local data by
`auth_user_id`. If no local record exists, it creates one containing only the email mapping
and VIP data, without storing nickname or avatar. If a local record with the same email
exists but is not bound yet, it is linked during first authentication. If the email is
already bound to another NekoAuth user, login is rejected to prevent account mixing.

### Configuration

Enter the following runtime settings in the NekoMusic admin authentication settings. Do
not hard-code them in source code.

| Setting | Description |
| --- | --- |
| `oauth.issuer` | Public NekoAuth root URL, for example `https://auth.nekocore.cn`, without a trailing `/` |
| `oauth.client_id` | ID of a **confidential client** created in NekoAuth |
| `oauth.client_secret` | Client secret, stored only on the NekoMusic server |
| `oauth.redirect_uri` | Must exactly match the URI registered in NekoAuth; default is `https://music.nekocore.cn/sso/nekocore/callback` |
| `oauth.scopes` | Scopes requested by browser login; must include `openid profile email`; the default also includes `roles` |

The client must be enabled with `publicClient=false`. The OIDC flow requires
`openid profile email`; the default configuration also requests `roles`. First-party
clients may be given a longer access-token lifetime in NekoAuth, but tokens remain bound to
the Auth browser session family and become invalid immediately when a device is revoked or
the user signs out of Auth. NekoAuth does not automatically register clients, and NekoMusic
does not contain a hard-coded client ID or secret. Rotate the NekoMusic configuration
whenever the client secret is rotated.

### Single Sign-On

Browser login is handled entirely by NekoAuth. NekoMusic acts as a standard OIDC
confidential client:

- NekoMusic reads authorization, token, UserInfo, and JWKS endpoints from NekoAuth's
  `/.well-known/openid-configuration`; endpoint URLs are not hard-coded.
- `POST /api/user/sso/start`: request body `{"returnTo":"<site-relative-path>"}`, response
  `{url}`. Authorization uses the authorization-code flow with PKCE S256, a one-time
  `state`, and an OIDC `nonce`. The frontend then navigates to NekoAuth.
- `GET /sso/nekocore/callback`: canonical callback. The server validates `state`, exchanges
  the authorization code and `code_verifier` for an access token / ID Token, validates the
  ID Token signature, issuer, audience, `azp`, `nonce`, `at_hash`, and `sub` through the
  NekoAuth JWKS, then reads UserInfo. The legacy `/oauth/nekocore/callback` remains as an
  alias.
- `GET /sso/complete`: the frontend completion page immediately removes the handoff from
  the address bar, calls `POST /api/user/sso/claim`, stores the token in
  `localStorage.userToken`, and returns to the original `returnTo` path. The handoff is valid
  for 120 seconds and can be claimed only once. The legacy `/oauth/nekocore/complete`
  redirects here.
- `POST /api/user/sso/claim`: request body `{"handoff":"..."}`. On success it returns
  `{token, returnTo, user}`, where `token` is a NekoAuth access token and `user` is local
  NekoMusic business data. The response uses `Cache-Control: private, no-store`.
- `POST /api/user/sso/password-reset-url`: returns the full NekoAuth `/forgot-password`
  URL. The frontend then navigates to it directly.

Android and PC call `/api/user/sso/start` with `client=android` and `client=pc`
respectively. After NekoAuth authorization, the server redirects to the in-site HTTPS
bridge page `/sso/native-complete`, which opens `nekomusic://sso/complete`. The app then
calls `POST /api/user/sso/claim` to exchange the handoff for a token. Long-lived tokens do
not appear in the deep link or browser history. The HTTPS bridge page keeps a manual button
for browsers that block automatic custom-protocol navigation.

Registration, password recovery, and account-profile changes all open NekoAuth: the
current browser on Web, an in-app WebView on Android, and the system browser on PC.

The Web login button uses the standard NekoAuth OAuth authorization-code flow. Auth writes
a read-only `nekoauth_session_hint` cookie on the parent domain. When the hint is present
and no local token exists, NekoMusic starts a silent `prompt=none` authorization request.
The hint itself contains no identity credential.

NekoMusic Web logout first revokes the current access token, then ends the Auth browser
session through standard OIDC RP-Initiated Logout. When a device is revoked in the
NekoAuth admin console, the OAuth authorization chain and NekoMusic access tokens bound to
that session family are invalidated immediately. Web periodically revalidates the login
state. Android and PC request `GET /api/user/info` again when returning to the foreground
and clear their local login state on `401`, without waiting for a restart or another
restricted action.

When upgrading an existing deployment, update both the registered NekoAuth callback and
`oauth.redirect_uri` to `https://music.nekocore.cn/sso/nekocore/callback`. The legacy
`/oauth/nekocore/callback` remains compatible, but new deployments should use the
canonical URI.

Browsers and legacy clients share the same `localStorage.userToken` / `Authorization`
field, but the token is now a NekoAuth access token instead of a local random string.
NekoMusic validates identity through NekoAuth introspection on each protected request and
revokes tokens through NekoAuth on logout. Password changes, administrator password
resets, and account suspension advance the NekoAuth token version and invalidate old
tokens immediately.

### Account Flows

- Login: new Web, Android, and PC clients use NekoAuth OIDC single sign-on. Only the
  NekoAuth access token is stored, and local business data is associated through `sub`.
- Registration: new clients no longer provide email, password, or verification-code forms.
  They open the NekoAuth registration page: an in-app WebView on Android and the browser on
  Web and PC.
- Profile and security: nickname, email, password, email verification, and signed-in
  devices are managed in the NekoAuth account center. The NekoMusic profile page only
  displays local business data and links to the account center.
- Avatar and VIP: avatars are maintained in the NekoAuth account center, and NekoMusic's
  avatar API is a read-only proxy. VIP status and expiry remain local to NekoMusic.
- Legacy client migration: email/password login, registration, verification codes,
  password recovery, and password-change APIs have been removed. Legacy clients must
  upgrade to the NekoAuth login page or QR-code login.
- QR-code login: when the phone confirms, the current valid NekoAuth access token is placed
  in a one-time QR session and claimed by the PC through the existing `deliveryKey`. No
  internal token exchange is used.
- Logout: NekoMusic revokes the current access token first, then uses standard OIDC
  RP-Initiated Logout to end the Auth browser session. Redis keeps no copy of user tokens.
- User management: NekoMusic admin no longer manages ordinary users. User creation,
  password resets, avatars, and nicknames are handled in NekoAuth.

OAuth, QR-login, and account-profile errors use the existing error-response format.
`Retry-After` semantics are preserved when waiting is required. Logs contain only the
operation type, status, and user ID; passwords, verification codes, and client secrets are
never logged.

## Local Frontend Development

```bash
cd frontend
npm install
npm run dev
```

The dev server (default `http://localhost:5173`) proxies `/api` and `/version` to the local
backend, keeping the frontend, API, audio, and covers on the same origin. **CORS does not
need to be enabled for browsers.** The proxy target is read from
`VITE_DEV_PROXY_TARGET` in `frontend/.env.development` and defaults to
`http://localhost:65535`. To point at another backend, such as
`https://music.nekocore.cn`, create `frontend/.env.development.local` and override the
value. This local file is not committed.

## Music Recognition (Backend)

The backend exposes `POST /api/music/recognize`, which accepts an `audio` field in
`multipart/form-data`. The server uses local FFmpeg to convert the recording to PCM and
matches it against fingerprints built from the local `Music/{id}.*` library. It does not
call third-party recognition APIs and does not forward audio to external services.

The backend warms the index proactively at startup. Per-track fingerprints and the
directly restorable library-wide inverted index are persisted in `Music/.fingerprints/`.
When audio or catalog metadata changes, the index is rebuilt in the background and swapped
atomically; the last usable index remains available during the rebuild.

The streaming endpoint `WS /api/music/recognize/ws` is also available. Clients can send
16 kHz mono PCM chunks while recording. The server repeatedly attempts matching and
returns immediately when it finds a match. The existing HTTP endpoint remains compatible.

Example:

```bash
curl -sS -F 'audio=@sample.m4a' https://music.example.com/api/music/recognize
```

The `data` object contains the song ID, title, artist, album, confidence, and matched
offset. If the recording is not in the local library, the response is `matched: false`.
Size, duration, concurrency, and rate-limit settings are available in **Admin → System
Settings → Music Recognition**. Backend restart is required after changing them.

## Sensitive-Word Check API

This API separates blocked-word validation from upload and registration flows so clients
can pre-check titles, usernames, playlist names, and similar text before submission. The
word lists are stored in `backend/src/main/resources/违禁词/` (`主词表.txt`,
`英文词表.txt`, and `白名单.txt`) and match the production lists.

**Base URL:** same as the site API, for example `https://music.nekocore.cn`

**Endpoint:** `POST /api/sensitive-word/check`

**Authentication:** not required

**Request headers:**

```
Content-Type: application/json
```

### Single Check

**Request body:**

```json
{
  "text": "爱上你"
}
```

**Success response (no match):**

```json
{
  "success": true,
  "message": "检测完成",
  "contains": false,
  "data": {
    "text": "爱上你",
    "contains": false,
    "words": []
  }
}
```

**Success response (match):**

```json
{
  "success": true,
  "message": "检测完成",
  "contains": true,
  "data": {
    "text": "示例文本",
    "contains": true,
    "words": ["命中词1"]
  }
}
```

`words` contains the matched blocked-word fragments, using the same logic as the upload
interceptor.

### Batch Check

**Request body:**

```json
{
  "texts": ["歌曲标题", "歌手名", "专辑名"]
}
```

**Response example:**

```json
{
  "success": true,
  "message": "检测完成",
  "contains": true,
  "data": [
    {
      "text": "歌曲标题",
      "contains": false,
      "words": []
    },
    {
      "text": "歌手名",
      "contains": true,
      "words": ["示例"]
    }
  ]
}
```

The top-level `contains` indicates whether **any** item matched. Each item in `data` has
the same shape as a single-check result.

### Limits and Errors

| Item | Description |
| --- | --- |
| Single text length | At most 2,000 characters |
| Batch size | At most 20 items |
| Whitespace | Spaces and zero-width characters are removed before checking, matching other backend validation |

**Error response:**

```json
{
  "message": "请提供 text 或 texts 字段"
}
```

HTTP status: `400` for invalid parameters.

### curl Examples

```bash
curl -s -X POST 'https://music.nekocore.cn/api/sensitive-word/check' \
  -H 'Content-Type: application/json' \
  -d '{"text":"爱上你"}'
```

```bash
curl -s -X POST 'https://music.nekocore.cn/api/sensitive-word/check' \
  -H 'Content-Type: application/json' \
  -d '{"texts":["标题","歌手"]}'
```

### Notes

- This API only checks text and does not write to the database. Upload, registration,
  playlist, and other write paths still validate again.
- The backend JAR must be redeployed after changing the word lists.

## Song Comments

Every song supports comments and nested floor replies. The comment button in the Web player
opens the thread. Replying to a reply stays in the same floor and marks the mentioned
user. There is a single endpoint, `/api/comments`:

- `GET /api/comments?musicId=&page=&pageSize=`: list comments, including replies.
- `POST /api/comments` with `{musicId, content, parentId?}`: create a comment or reply
  (login required).
- `DELETE /api/comments?id=`: delete your own comment; administrators may delete any
  comment.

Deletion is **physical**. Deleting a floor also deletes all replies in that floor, and the
list does not leave placeholders such as "comment deleted".

Comments return the posting time in UTC+8 wall-clock time, IP region, and author. Clients
fetch avatars from `/api/user/avatar/{userId}`. For the complete field list and examples,
see [Neko歌姬计划文档/README.md](Neko歌姬计划文档/README.md#歌曲评论-api).

### IP Region

Region lookup uses **no third-party APIs**. It first queries the local ASN database and
maps province-level carrier ASNs (for example, `AS56045` → Jiangxi) to provinces. For
nationwide ASNs or misses, it falls back to the MaxMind geographic database for country and
province/city, and then uses qqwry for IPv4. The default JAR includes free GeoLite2
fallback databases and extracts them at startup when the runtime directory does not contain
them. If the disk is not writable, they are read from memory:

- `backend/src/main/resources/GeoLite2-City.mmdb` (GeoLite2 City, IPv4/IPv6)
- `backend/src/main/resources/GeoLite2-ASN.mmdb` (GeoLite2 ASN, IPv4/IPv6)
- `backend/src/main/resources/GeoIP/asn_regions.tsv` (province-level carrier ASN mapping)
- `backend/src/main/resources/GeoIP/qqwry.dat` (QQWry, IPv4)

The geographic and ASN databases are searched in `GeoIP/` (runtime directory),
`/app/GeoIP/` (inside the container), the system GeoIP directory, and JAR resources, in
that order. qqwry similarly prefers `GeoIP/` (`/app/GeoIP/` in containers) and then loads
from the JAR. Operators can place commercial MaxMind databases in those locations to
override the bundled versions. The code selects databases in this order:

- Geographic database: `GeoIP2-Enterprise.mmdb` → `GeoIP2-City.mmdb` →
  `GeoIP2-Country.mmdb` → matching `GeoLite2-*`
- ASN database: `GeoIP2-ISP.mmdb` (ASN field) → `GeoLite2-ASN.mmdb`

Commercial databases must be purchased separately and used under the MaxMind license.
**Do not commit them to the repository or ship them in public builds.** Mount them into
`GeoIP/` or `/app/GeoIP/` to load them first at startup. ASN mappings only include
province-level carrier networks; nationwide ASNs such as China Mobile `9808`, China Telecom
`4134`, and China Unicom `4837` are not forced to their registration province.

Display rules:

- Ordinary mainland provinces show only the province-level region, such as `江西` or `广东`,
  not a prefecture-level city.
- Beijing, Shanghai, Tianjin, and Chongqing are province-level municipalities and display
  as `北京`, `上海`, `天津`, and `重庆`.
- Hong Kong, Macao, and Taiwan use the `中国` prefix: `中国香港`, `中国澳门`, `中国台湾`.
- Other countries show only the country name, without province or city.
- LAN and loopback addresses display as `本地`.
- If a mainland province cannot be resolved, the display is `中国`; if no source can
  identify the location, it is `其他`.

If the databases are missing, the service keeps running. `ip_region` is a snapshot taken
when the comment is posted and is not recalculated automatically after database upgrades.
Historical `unknown` / `未知` values are displayed as `其他`. The original client IP is
stored separately in `music_comments.client_ip` for server-side verification and future
re-resolution and is never returned by the comment API.

## Daily Recommendations (Redis)

The backend generates a daily recommendation list from each user's favorite styles using
rule-based recall and ranking, without external services.

### Behavior

- A full daily refresh runs at **00:00 UTC+8** after the server registers the scheduler.
- Recommendation results are stored **only in Redis**, not in a MySQL recommendation table.
- **Favorites are hard-filtered**: tracks in `user_favorites` never appear in the list.
- **Playlist tracks are down-ranked**: tracks from user-created or favorited playlists may
  still be recommended, but receive a lower rule score and rank later.
- If the current day's cache is missing, the API generates it on demand and writes it back
  to Redis.

### Settings (Admin → System Settings → Daily Recommendations)

- `recommendation.daily_limit`: number of recommendations produced per user per day
  (default 300).

### Redis Storage

- Key: `daily_reco:{yyyy-MM-dd}:{userId}`
- Value: a JSON array containing
  `rank/musicId/title/artist/album/language/tags/score/source/reason`
- TTL: 72 hours for cross-day tolerance

### API

- `GET /api/user/recommendations/daily`
  - Requires a user login token (`Authorization`)
  - Returns the current day's recommendations
- `GET /api/user/recommendations/daily?refresh=true`
  - Forces a recomputation for the current user and replaces the Redis value

### Response Example

```json
{
  "success": true,
  "date": "2026-05-28",
  "count": 30,
  "data": [
    {
      "rank": 1,
      "musicId": 13751,
      "title": "天使ロード中…^_−☆",
      "artist": "三Z-STUDIO&HOYO-MiX",
      "album": "绝区零-天使加载中…^_−☆",
      "language": "日语",
      "tags": "二次元，日语，游戏",
      "score": 4.93,
      "source": "rule",
      "reason": "基于收藏风格匹配"
    }
  ]
}
```

### Operations

- Enable Redis persistence (RDB or AOF) to avoid losing the current day's recommendation
  cache after a restart.
- Increasing `daily_limit` linearly increases candidate recall work and response size.

## Recommended Playlists (Real-Time, Uncached)

The home page displays up to five recommended playlists. This feature is independent of
daily song recommendations.

### Ranking Strategy

- **Signed-in users:** build a profile from favorites, user-created playlists, favorited
  playlists, and recent searches, then rank playlists using artist/tag/language cosine
  similarity, collaborative filtering from similar users, creator affinity, community
  favorites, play counts, and freshness.
- **Guests:** do not read or write guest search history. Recommend by community favorites,
  play counts, playlist completeness, and freshness, while limiting how many slots one
  creator can occupy.
- **Refresh:** keep the top three high-confidence results and replace two exploration slots
  from later candidates. Default results remain stable.
- Recommendation responses are not written to Redis and are not cached in-process. They
  explicitly return `Cache-Control: private, no-store`.

### Search Profile

- A normalized search term is recorded asynchronously only when the user is signed in and
  the search has local results.
- The same query is counted at most once every 10 minutes. Search profiles are retained for
  180 days, and new entries stop after logout.
- Search profiles are used only for in-site personalized recommendations, not third-party
  advertising or commercial profiling.

### API

- `GET /api/playlists/recommended`
  - `Authorization` is optional; without it, guest popular recommendations are returned
  - Returns up to five playlists
- `GET /api/playlists/recommended?refresh=true`
  - Regenerates the result and replaces the exploration slots

See the “获取推荐歌单” section in
[Neko歌姬计划文档/README.md](Neko歌姬计划文档/README.md) for the complete client contract.
