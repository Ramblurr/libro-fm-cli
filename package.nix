{
  lib,
  stdenv,
  makeWrapper,
  babashka,
}:

stdenv.mkDerivation rec {
  pname = "libro-fm-cli";
  version = "0.1.0";
  src = ./.;
  nativeBuildInputs = [ makeWrapper ];
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p $out/bin
    cp libro $out/bin/libro
    chmod +x $out/bin/libro
    wrapProgram $out/bin/libro \
      --prefix PATH : ${lib.makeBinPath [ babashka ]}
    runHook postInstall
  '';

  meta = with lib; {
    description = "Babashka CLI for libro.fm";
    homepage = "https://github.com/ramblurr/libro-fm-cli";
    license = licenses.mit;
    maintainers = with maintainers; [ lib.maintainers.ramblurr ];
    platforms = babashka.meta.platforms;
    mainProgram = "libro";
  };
}
