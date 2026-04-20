# libro-fm-cli

A simple CLI tool for interacting with [libro.fm](https://libro.fm).

## Development

```bash
nix develop
bb ci
bb build
./libro help
```

## Configuration

The CLI stores config and state in XDG paths via `com.outskirtslabs/dirs`.
For local testing, set `LIBRO_FM_EMAIL` and `LIBRO_FM_PASSWORD`.

## License: European Union Public License 1.2

Copyright © 2026 Casey Link <unnamedrambler@gmail.com>
Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
