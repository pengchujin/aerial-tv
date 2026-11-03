cmake_minimum_required(VERSION 3.22.1)
project(aerial_tcl_backlight C)

add_library(aerial_tcl_backlight SHARED tcl_backlight.c)
target_link_libraries(aerial_tcl_backlight PRIVATE dl)
target_compile_options(aerial_tcl_backlight PRIVATE -Wall -Wextra -Werror)
set_target_properties(aerial_tcl_backlight PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
