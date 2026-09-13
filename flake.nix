{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    flake-utils.url = "github:numtide/flake-utils";

    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      nixpkgs,
      flake-utils,
      rust-overlay,
      ...
    }:
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;

          overlays = [
            rust-overlay.overlays.default
          ];

          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        importYAML =
          file:
          builtins.fromJSON (
            builtins.readFile (
              pkgs.runCommand "converted-yaml.json" { } ''
                ${pkgs.yj}/bin/yj < ${file} > "$out"
              ''
            )
          );

        androidRelease =
          importYAML ./.github/workflows/release-android.yml;

        # this must be a full version, since it's used to find aapt2
        buildToolsVersion =
          androidRelease.env.ANDROID_BUILD_TOOLS;

        androidApi =
          androidRelease.env.ANDROID_API;

        ndkVersion =
          androidRelease.env.ANDROID_NDK_VERSION;

        androidComposition =
          pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ androidApi ];
            buildToolsVersions = [ buildToolsVersion ];

            includeNDK = true;
            ndkVersions = [ ndkVersion ];
          };

        androidSdk =
          androidComposition.androidsdk;

        androidSdkRoot =
          "${androidSdk}/libexec/android-sdk";

        androidNdkRoot =
          "${androidSdkRoot}/ndk/${ndkVersion}";

        rustConfiguration =
          builtins.fromTOML (
            builtins.readFile ./rust-toolchain.toml
          );

        rustVersion =
          rustConfiguration.toolchain.channel;

        rustToolchain =
          pkgs.rust-bin.stable.${rustVersion}.default.override {
            targets = [
              "aarch64-linux-android"
              "armv7-linux-androideabi"
              "x86_64-linux-android"
              "i686-linux-android"
            ];
          };

        jdk21Home =
          "${pkgs.openjdk21}/lib/openjdk";
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            androidSdk
            pkgs.openjdk21

            rustToolchain
            pkgs.cargo-ndk
          ];

          JAVA_HOME = jdk21Home;

          ANDROID_HOME = androidSdkRoot;
          ANDROID_SDK_ROOT = androidSdkRoot;

          ANDROID_NDK_HOME = androidNdkRoot;
          ANDROID_NDK_ROOT = androidNdkRoot;

          # Read by shared/build.gradle.kts (cargoBinDefault).
          MAGES_CARGO = "${rustToolchain}/bin/cargo";

          GRADLE_OPTS = builtins.concatStringsSep " " [
            "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdkRoot}/build-tools/${buildToolsVersion}/aapt2"
            "-Dorg.gradle.java.installations.paths=${jdk21Home}"
            "-Dorg.gradle.java.installations.auto-download=false"
          ];
        };
      }
    );
}
