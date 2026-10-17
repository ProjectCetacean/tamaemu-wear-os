cmake_minimum_required(VERSION 3.22)
project(tamaemu_wear C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)
set(TAMAEMU_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(TAMAEMU_SOURCES
    ${TAMAEMU_ROOT}/src/cpu.c
    ${TAMAEMU_ROOT}/src/mem.c
    ${TAMAEMU_ROOT}/src/periph.c
    ${TAMAEMU_ROOT}/src/lcd.c
    ${TAMAEMU_ROOT}/src/device.c
    ${TAMAEMU_ROOT}/src/disasm.c
    ${TAMAEMU_ROOT}/src/pn512.c
    backend.c standalone.c sha256.c)
add_library(tamaemu_core STATIC ${TAMAEMU_SOURCES})
set_target_properties(tamaemu_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(tamaemu_core PUBLIC "${TAMAEMU_ROOT}/src" "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(tamaemu_core PUBLIC m)
option(TAMAEMU_BUILD_REPLAY_PROBE "Build standalone replay measurement tool (never linked into APK)" OFF)
if(ANDROID)
    add_library(tamaemu SHARED jni.c)
    target_link_libraries(tamaemu PRIVATE tamaemu_core)
    if(TAMAEMU_BUILD_REPLAY_PROBE)
        add_executable(bench_replay bench_replay.c)
        target_link_libraries(bench_replay PRIVATE tamaemu_core)
    endif()
    # Sideload builds retain Java debuggability/run-as and native symbols, but
    # instruction dispatch must be optimized even in the Android Debug variant.
    # Do not add fast-math or disable assertions for this configuration.
    target_compile_options(tamaemu_core PRIVATE "$<$<CONFIG:Debug>:-O2>")
    target_compile_options(tamaemu PRIVATE "$<$<CONFIG:Debug>:-O2>")
else()
    enable_testing()
    add_executable(test_backend test_backend.c)
    target_compile_definitions(test_backend PRIVATE WEAR_FAULT_TEST=1)
    target_link_libraries(test_backend PRIVATE tamaemu_core ${CMAKE_DL_LIBS})
    add_test(NAME backend COMMAND test_backend)
    add_executable(test_audio test_audio.c)
    target_link_libraries(test_audio PRIVATE tamaemu_core)
    add_test(NAME audio COMMAND test_audio)
    # Test-only frozen slow loops are never linked into an APK or probe.
    add_library(replay_instrumented_backend OBJECT backend.c)
    target_include_directories(replay_instrumented_backend PRIVATE "${TAMAEMU_ROOT}/src" "${CMAKE_CURRENT_LIST_DIR}")
    target_compile_definitions(replay_instrumented_backend PRIVATE cpu_step=replay_counted_cpu_step cpu_skip_idle=replay_counted_cpu_skip_idle)
    add_executable(test_replay test_replay.c test_replay_reference_backend.c test_replay_reference_periph.c
        $<TARGET_OBJECTS:replay_instrumented_backend>)
    target_link_libraries(test_replay PRIVATE tamaemu_core)
    target_compile_options(test_replay PRIVATE -O2)
    add_test(NAME replay_equivalence COMMAND test_replay)
    add_test(NAME replay_dispatch COMMAND test_replay --dispatch)
    add_library(replay_instrumented_periph OBJECT "${TAMAEMU_ROOT}/src/periph.c")
    target_include_directories(replay_instrumented_periph PRIVATE "${TAMAEMU_ROOT}/src")
    foreach(name write32 read32 write8 read8 ir_session_begin ir_session_state buttons nfc_field_edge speed_step tick)
        target_compile_definitions(replay_instrumented_periph PRIVATE "periph_${name}=replay_instrumented_periph_${name}")
    endforeach()
    target_compile_definitions(replay_instrumented_periph PRIVATE t16_extra_shift=replay_instrumented_t16_extra_shift)
    target_compile_options(replay_instrumented_periph PRIVATE -O2 -finstrument-functions)
    add_executable(test_replay_timers test_replay_timers.c test_replay_reference_periph.c $<TARGET_OBJECTS:replay_instrumented_periph>)
    target_compile_options(test_replay_timers PRIVATE -O2)
    target_link_libraries(test_replay_timers PRIVATE tamaemu_core)
    add_test(NAME replay_timers COMMAND test_replay_timers)
    add_test(NAME replay_timer_dispatch COMMAND test_replay_timers --dispatch)
    # Optional actual JVM/JNI boundary regression; firmware-free and host-only.
    # JAVA_HOME can point at a portable JDK; native-only builds need no JDK.
    find_package(Java QUIET COMPONENTS Runtime Development)
    find_package(JNI QUIET)
    if(Java_FOUND AND JNI_FOUND)
        add_library(tamaemu_jni_host SHARED jni.c)
        set_target_properties(tamaemu_jni_host PROPERTIES OUTPUT_NAME tamaemu)
        target_include_directories(tamaemu_jni_host PRIVATE ${JNI_INCLUDE_DIRS})
        target_link_libraries(tamaemu_jni_host PRIVATE tamaemu_core)
        set(JNI_TEST_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/jni-test-classes")
        set(JNI_TEST_SOURCE "${TAMAEMU_ROOT}/scripts/java/LifeJniProbe.java")
        set(JNI_BRIDGE_SOURCE "${TAMAEMU_ROOT}/wear/app/src/main/java/io/github/projectcetacean/tamaemu/NativeBridge.java")
        set(JNI_TEST_CLASS "${JNI_TEST_CLASSES}/io/github/projectcetacean/tamaemu/LifeJniProbe.class")
        add_custom_command(OUTPUT "${JNI_TEST_CLASS}"
            COMMAND ${CMAKE_COMMAND} -E make_directory "${JNI_TEST_CLASSES}"
            COMMAND ${Java_JAVAC_EXECUTABLE} --release 8 -d "${JNI_TEST_CLASSES}" "${JNI_BRIDGE_SOURCE}" "${JNI_TEST_SOURCE}"
            DEPENDS "${JNI_BRIDGE_SOURCE}" "${JNI_TEST_SOURCE}" VERBATIM)
        add_custom_target(native_life_jni_classes ALL DEPENDS "${JNI_TEST_CLASS}")
        add_test(NAME backend_jni COMMAND ${Java_JAVA_EXECUTABLE}
            "-Djava.library.path=$<TARGET_FILE_DIR:tamaemu_jni_host>"
            -cp "${JNI_TEST_CLASSES}" io.github.projectcetacean.tamaemu.LifeJniProbe)
    endif()
    # Experimental host-only replay probe; never linked into the Android app.
    add_executable(bench_replay bench_replay.c)
    target_link_libraries(bench_replay PRIVATE tamaemu_core)
endif()
