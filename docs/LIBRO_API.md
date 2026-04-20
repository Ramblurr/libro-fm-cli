# libro.fm API Reference

Unofficial notes on the private JSON API that the libro.fm mobile apps use.

Endpoints are undocumented, subject to change, and protected by an edge gate
(see "Edge gate and required headers"). If a previously-working request starts
failing with an empty-body `401` whose `server` header is `awselb/2.0`, the
gate has been tightened and the required header values probably need to be
refreshed from a current mobile app build.

## Base URL

    https://libro.fm

Responses are JSON unless otherwise noted. Binary downloads (mp3 zip parts,
m4b files, pdf extras) are served from signed S3 URLs returned by the API.

## Edge gate and required headers

Every request, including the unauthenticated login, is filtered at the
AWS ELB before it reaches the application. Requests that don't look like
the official mobile app are rejected with an empty-body `401 Unauthorized`
and `server: awselb/2.0`. The application itself, when reached, returns
JSON errors like `{"error": "Not authorized."}`, so the empty-body 401 is
diagnostic of an edge rejection rather than an auth failure.

The headers that pass the gate, as of 2026-04-20:

    Content-Type: application/json
    User-Agent: okhttp/5.3.2
    X-LibroFm-AppVer: 7.34.8

For authenticated calls, also send:

    Authorization: Bearer <access_token>

The `X-LibroFm-AppVer` value tracks the current Android app version; when the
gate tightens, a newer build number may be required. `okhttp/<version>`
identifies the HTTP client used by the Android app. Older versions
(`okhttp/3.14.9` observed in the TS reference) no longer pass the gate.

## Authentication

OAuth 2.0 password grant. The client sends the user's libro.fm email and
password and receives a bearer token that is used for every subsequent
request.

### POST /oauth/token

Request body:

    {
      "grant_type": "password",
      "username":   "user@example.com",
      "password":   "hunter2"
    }

Response (200):

    {
      "access_token": "..."
    }

The TokenMetadata envelope in the Kotlin reference only exposes
`access_token`; other fields (refresh token, expiry, scope) may exist but
are not relied upon. Tokens appear to be long-lived in practice — the two
reference clients persist them to disk and reuse them indefinitely until a
401 forces a re-login.

## Library

### GET /api/v10/library

Query parameters:

- `page` (int, default 1)

Returns a paginated list of the user's owned audiobooks.

Response shape:

    {
      "page":         1,
      "total_pages":  3,
      "audiobooks":   [ Book, ... ],
      "tags":         [ ... ]
    }

Clients typically iterate `page` from 1 to `total_pages`, concatenating
`audiobooks` across pages.

### Book object

As observed in the Kotlin model (v10 responses may include additional
fields that clients should ignore):

    {
      "title":            "...",
      "authors":          ["..."],
      "isbn":             "9780000000000",
      "cover_url":        "https://.../cover.jpg",
      "publisher":        "...",
      "publication_date": "2020-01-01T00:00:00Z",
      "description":      "...",
      "genres":           [ { "name": "Fiction" }, ... ],
      "series":           "Optional series name",
      "series_num":       1,
      "audiobook_info": {
        "narrators":   ["..."],
        "duration":    48300,
        "track_count": 12,
        "pdf_extras":  [ { "filename": "booklet.pdf" }, ... ]
      }
    }

`duration` is in seconds. `pdf_extras` is empty for most books.

### GET /api/v10/explore/audiobook_details/{isbn}

Returns full details for a single audiobook. Response is wrapped in a
`data.audiobook` envelope:

    {
      "data": {
        "audiobook": Book
      }
    }

## Downloads

Libro.fm supports two distinct download formats for a given ISBN: a bundle
of mp3 tracks split across one or more zip parts, and a single m4b file.
They live at separate endpoints.

### GET /api/v10/download-manifest

Query parameters:

- `isbn` (string, required)

Returns URLs for downloading the audiobook as mp3 parts, plus per-track
chapter titles.

Response:

    {
      "parts": [
        { "url": "https://.../part-1.zip", "size_bytes": 123456789 },
        ...
      ],
      "tracks": [
        { "number": 1, "chapter_title": "Chapter 1" },
        ...
      ]
    }

Each zip in `parts` contains a slice of the mp3 tracks. Clients concatenate
the extracted files in order to reconstruct the full audiobook. The signed
S3 URLs expire; re-fetch the manifest if a download fails with 403.

### GET /api/v10/audiobooks/{isbn}/packaged_m4b

Returns metadata pointing at a single packaged m4b file. The Kotlin
reference models this as a generic `M4bMetadata` and treats a non-success
HTTP status as "M4B not available for this title" — not every book has an
m4b package prepared.

The response is a JSON document whose shape includes at least a download
URL pointing at S3. The URL's query string carries a
`response-content-disposition` parameter of the form
`filename="Book+Title.m4b"`; clients parse the filename from that
parameter and replace `+` with spaces before saving to disk.

### GET /api/v10/library/{isbn}/pdf_extra_url

Query parameters:

- `filename` (string, required) — one of the `filename` values listed in
  the book's `audiobook_info.pdf_extras`.

Response:

    {
      "pdf_url": "https://..."
    }

The returned URL is a time-limited signed URL for the pdf.

## Wishlist

### GET /api/v10/explore/wishlist

Returns the current user's wishlist, wrapped in a `data.wishlist` envelope:

    {
      "data": {
        "wishlist": {
          "audiobooks": [
            { "title": "...", "isbn": "...", "authors": ["..."] },
            ...
          ]
        }
      }
    }

The wishlist audiobook objects are a subset of the full `Book` schema.

### POST /api/v10/explore/wishlist/{isbn}

Adds an ISBN to the current user's wishlist. No request body. Returns an
empty body; the Kotlin reference treats any 2xx as success.

## Binary downloads

Binary assets (mp3 zip parts, m4b files, pdfs) are served by S3 at the
signed URLs returned in the manifest/metadata responses. These S3 URLs do
not require the libro.fm edge headers or the `Authorization` header — the
signature in the URL's query string authorises the request. A plain GET is
sufficient.

Time-to-live on the signatures is short (typically minutes). Long-running
clients should refetch the manifest if a download fails with 403.

## Error behaviour

- Edge rejection: empty response body, status 401, `server: awselb/2.0`.
  Cause: one of the required headers is missing or outdated. Fix by
  refreshing `User-Agent` / `X-LibroFm-AppVer` to match a current build.
- Application auth failure: status 401, JSON body like
  `{"error": "Not authorized."}`. Cause: missing, malformed, or expired
  bearer token. Fix by re-running the login flow.
- Not found / no m4b: the `packaged_m4b` endpoint returns a non-2xx
  status for titles without a prepared m4b. Fall back to the mp3 manifest.

## API version drift

The two reference clients disagree on version prefixes:

- `libro-client` (TS) uses `/api/v7/library` and `/api/v9/download-manifest`.
- `librofm-downloader` (Kotlin) uses `/api/v10/` for everything above.

As of 2026-04-20 the v10 endpoints work for our traffic and the TS v7/v9
endpoints still respond for library listing but have not been retested
against the current edge gate. Prefer v10 when adding new calls. When an
endpoint stops working, check whether a newer version prefix exists before
assuming the shape has changed.
