cmake_minimum_required(VERSION 3.22.1)
project(pocket_native C)

add_library(pocketspawn SHARED pocket_spawn.c)

# Android 15+ devices may use 16 KB memory pages. Keep every PT_LOAD segment
# aligned so Google Play and the OS do not need page-size compatibility mode.
target_link_options(pocketspawn PRIVATE "-Wl,-z,max-page-size=16384")
