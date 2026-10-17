# Third-party notices

The emulator and this Wear OS port, modified in October 2026, retain the GNU
GPL version 3 license in [LICENSE](LICENSE). Upstream source and attribution
remain in the repository, including
[maragotchi/tamaemu](https://github.com/maragotchi/tamaemu), the GMMan SLEIGH
reference in `src/cpu.c`, and the packaging resource notices.

Two checked-in build/test components use Apache License 2.0. A full copy is
provided in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

| Component | Location and origin | Notice |
| --- | --- | --- |
| Gradle wrapper 8.13 | `wear/gradlew`, `wear/gradlew.bat`, `wear/gradle/wrapper/gradle-wrapper.jar`; [Gradle 8.13 source](https://github.com/gradle/gradle/tree/v8.13.0) | Unix launcher: Copyright © 2015-2021 the original authors. Windows launcher: Copyright 2015 the original author or authors. The unmodified wrapper JAR includes `META-INF/LICENSE`. |
| kotlinx.coroutines 1.8.1 test resource | The base64-encoded, unmodified `DebugProbesKt.bin` in `scripts/tests/test_inspect_wear_privacy.py`, from the [official Maven Central JAR](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar) | Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors, as stated in the [version 1.8.1 license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.8.1/LICENSE.txt). |

The fixture exists to verify that APK privacy checks allow only that exact
public dependency resource. It is not firmware. Its SHA-256 is
`86fecd0235d45839845081c1f91c9a58b40b0ae787f242bdba8f9150da45d0eb`.
The wrapper JAR SHA-256 is
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.

The Apache license copy is reproduced from the pinned kotlinx.coroutines
1.8.1 source, including its attribution. Preserve dependency-provided license
and notice resources when distributing APKs. These notices describe the
checked-in components above; they do not replace an audit of the dependencies
resolved for a particular binary release. Firmware is separate and is not
included in this repository or covered by these notices.
