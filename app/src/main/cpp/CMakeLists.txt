cmake_minimum_required(VERSION 3.10.2)

add_library(tunnel SHARED tunnel.c)

find_library(log-lib log)

target_link_libraries(tunnel ${log-lib})