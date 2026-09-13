{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    inputs:
    inputs.flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import inputs.nixpkgs {
          inherit system;
          config.android_sdk.accept_license = true;
          config.allowUnfree = true;
        };

        androidRelease =
          let
            importYAML =
              file:
              builtins.fromJSON (
                builtins.readFile (
                  pkgs.runCommand "converted-yaml.json" { } ''${pkgs.yj}/bin/yj < "${file}" > "$out"''
                )
              );
          in
          importYAML ./.github/workflows/release-android.yml;

        # this must be a full version, since it's used to find aapt2
        buildToolsVersion = androidRelease.env.ANDROID_BUILD_TOOLS;

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ androidRelease.env.ANDROID_API ];
          buildToolsVersions = [ buildToolsVersion ];
          includeNDK = true;
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            # not really required here
            androidSdk

            openjdk21

            cargo
            rustc
          ];

          # source: https://wiki.nixos.org/wiki/Android#gradlew
          # override the aapt2 that gradle uses with the nix-shipped version
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        };
      }
    );
}
