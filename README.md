# libro-fm-cli

A simple CLI tool for interacting with [libro.fm](https://libro.fm).

## Usage

Build the script, then run `./libro help` to see the available commands.

```bash
./libro login
./libro list
./libro files 9780063003934
./libro get 9780063003934 ./tmp-dl
./libro get 9780063003934 ./tmp-dl --format m4b
```

### Login

`login` performs a libro.fm password-grant login and caches the returned
access token on disk.

By default, the config file lives at:

```text
~/.config/libro-fm-cli/config.edn
```

If `XDG_CONFIG_HOME` is set, the CLI uses:

```text
$XDG_CONFIG_HOME/libro-fm-cli/config.edn
```

The config file stores your libro.fm username and cached auth token. It is
written with owner-only permissions.

You can log in interactively:

```bash
./libro login
```

Or provide credentials through environment variables:

```bash
LIBRO_FM_EMAIL="you@example.com" \
LIBRO_FM_PASSWORD="secret" \
./libro login
```

The same environment variables are also respected by `list`, `files`, and
`get`. Environment values override values already stored in `config.edn`.

### Commands

- `./libro list` lists books in your library.
- `./libro files ISBN` shows which downloadable assets exist for a book:
  preferred format, `m4b` availability, `mp3` part/track counts, and PDF
  extras.
- `./libro get ISBN DIR` downloads a book into `DIR`.
- `./libro get ISBN DIR --format m4b` requires `m4b` and errors if it is not
  available.
- `./libro get ISBN DIR --format mp3` requires `mp3` parts and errors if they
  are not available.

Without `--format`, `get` prefers `m4b` and falls back to `mp3` when no
packaged `m4b` exists.

### Machine-readable output

Most commands support `--json` and `--edn`:

```bash
./libro list --edn
./libro files 9780063003934 --json
./libro get 9780063003934 ./tmp-dl --edn
```

## Development

```bash
nix develop
bb ci
bb build
./libro help
```

## Configuration

The CLI uses XDG config paths via `com.outskirtslabs/dirs`.

Supported environment variables:

- `LIBRO_FM_EMAIL`
- `LIBRO_FM_PASSWORD`

These override stored config values when present.

## License: European Union Public License 1.2

Copyright © 2026 Casey Link

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
