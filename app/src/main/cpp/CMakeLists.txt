cmake_minimum_required(VERSION 3.22.1)
project("tunnel_terminal")

add_library(tunnel_terminal SHARED native-lib.cpp)

find_library(log-lib log)
target_link_libraries(tunnel_terminal ${log-lib})
