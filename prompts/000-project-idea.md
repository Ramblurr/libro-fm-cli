
this directory is freshly created from my nix flake skeleton/template

we will turn it into libro-fm-cli

libro-fm-cli is a clojure babashka cli tool for interacting with libro.fm

the command will be 'libro'

Use this project as a reference for how to interact with libro.fm /home/ramblurr/src/github.com/jedwards1230/libro-client

however for the project structure/skeleton and for how to build a cli babashka script use ~/src/github.com/ramblurr/tmux-buddy as the primary reference

you must use the following BUILT IN babashka deps:

cheshire for json
babashka.cli for cli opts/args parsing
babashka.fs if you need to do fs stuff
babashka.process if you need to exec sub proceses
babashka http-client for http requests
if you need to store data in .config or cache or something use the com.outskirtslabs/dirs library for xdg_*_home. use libro-fm-cli as the project/app name
ref ~/src/github.com/outskirtslabs/dirs


