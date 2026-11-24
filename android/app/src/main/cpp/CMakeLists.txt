# Native part of LOBBYISM: the AprilTag marker detector (BSD-2-Clause, vendored in ./apriltag)
# plus a small JNI bridge. No other native dependencies.
cmake_minimum_required(VERSION 3.22.1)
project(lobbyism_marker C)

file(GLOB APRILTAG_COMMON apriltag/common/*.c)
add_library(lobbyism_marker SHARED
    lobbyism_marker.c
    apriltag/apriltag.c
    apriltag/apriltag_pose.c
    apriltag/apriltag_quad_thresh.c
    apriltag/tag36h11.c
    ${APRILTAG_COMMON})

target_include_directories(lobbyism_marker PRIVATE apriltag apriltag/common)
# Optimised even in debug builds: the detector runs on every analysed camera frame.
target_compile_options(lobbyism_marker PRIVATE -O2 -std=gnu99 -Wno-unused-parameter -Wno-sign-compare)
target_link_libraries(lobbyism_marker m log)
