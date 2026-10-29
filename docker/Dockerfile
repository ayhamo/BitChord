# Build image for the BitChord fork: JDK 17 + Android SDK, NDK and CMake.
# Versions follow the project: compileSdk 37, AGP 8.10.1 (build-tools 35, NDK 27), CMake 3.22.1.
FROM eclipse-temurin:17-jdk

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin

RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    && unzip -q /tmp/cmdline.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdline.zip

RUN yes | sdkmanager --licenses > /dev/null \
    # AGP only resolves compileSdk 37 ("android-37.0" on disk) when platform-tools is installed too.
    && sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;35.0.0" \
       "ndk;27.0.12077973" "cmake;3.22.1" > /dev/null

WORKDIR /work
