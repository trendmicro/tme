# Try to find protocol buffers (protobuf)
#
# Use as FIND_PACKAGE(ProtocolBuffers)
#
#  PROTOBUF_FOUND - system has the protocol buffers library
#  PROTOBUF_INCLUDE_DIR - the zip include directory
#  PROTOBUF_LIBRARY - Link this to use the zip library
#  PROTOBUF_PROTOC_EXECUTABLE - executable protobuf compiler
#
# And the following command
#
#  WRAP_PROTO(VAR input1 input2 input3..)
#
# Which will run protoc on the input files and set VAR to the names of the created .cc files,
# ready to be added to ADD_EXECUTABLE/ADD_LIBRARY. E.g,
#
#  WRAP_PROTO(PROTO_SRC myproto.proto external.proto)
#  ADD_EXECUTABLE(server ${server_SRC} {PROTO_SRC})
#
# Author: Esben Mose Hansen <[EMAIL PROTECTED]>, (C) Ange Optimization ApS 2008
#
# Redistribution and use is allowed according to the terms of the BSD license.
# For details see the accompanying COPYING-CMAKE-SCRIPTS file.

IF (PROTOBUF_LIBRARY AND PROTOBUF_INCLUDE_DIR AND PROTOBUF_PROTOC_EXECUTABLE)
  # in cache already
  SET(PROTOBUF_FOUND TRUE)
ELSE (PROTOBUF_LIBRARY AND PROTOBUF_INCLUDE_DIR AND PROTOBUF_PROTOC_EXECUTABLE)

  FIND_PATH(PROTOBUF_INCLUDE_DIR stubs/common.h
    /usr/include/google/protobuf
    /usr/local/include/google/protobuf
  )

  FIND_LIBRARY(PROTOBUF_LIBRARY NAMES protobuf
    PATHS
    ${GNUWIN32_DIR}/lib
  )

  FIND_PROGRAM(PROTOBUF_PROTOC_EXECUTABLE protoc)

  INCLUDE(FindPackageHandleStandardArgs)
  FIND_PACKAGE_HANDLE_STANDARD_ARGS(protobuf DEFAULT_MSG PROTOBUF_INCLUDE_DIR PROTOBUF_LIBRARY PROTOBUF_PROTOC_EXECUTABLE)

  # ensure that they are cached
  SET(PROTOBUF_INCLUDE_DIR ${PROTOBUF_INCLUDE_DIR} CACHE INTERNAL "The protocol buffers include path")
  SET(PROTOBUF_LIBRARY ${PROTOBUF_LIBRARY} CACHE INTERNAL "The libraries needed to use protocol buffers library")
  SET(PROTOBUF_PROTOC_EXECUTABLE ${PROTOBUF_PROTOC_EXECUTABLE} CACHE INTERNAL "The protocol buffers compiler")

ENDIF (PROTOBUF_LIBRARY AND PROTOBUF_INCLUDE_DIR AND PROTOBUF_PROTOC_EXECUTABLE)

IF (PROTOBUF_FOUND)
  # Define the compile_protobuf_proto function
  function(compile_protobuf_proto)
    if(NOT ARGN)
      message(
        SEND_ERROR
        "Error: compile_proto called without any proto files (NO extension)")
      return()
    endif(NOT ARGN)

    set(PROTOBUF_GENERATED_H_FILES)
    set(PROTOBUF_GENERATED_CC_FILES)
    set(PROTO_FILES_ABS)

    foreach(PROTO_BASE ${ARGN})
      set(PROTO_FILE
        ${PROTO_BASE}.proto)
      get_filename_component(PROTO_FILE_ABS ${PROTO_FILE} ABSOLUTE)
      list(
        APPEND PROTO_FILES_ABS
        "${PROTO_FILE_ABS}")
      list(
        APPEND PROTOBUF_GENERATED_CC_FILES
        "${CMAKE_CURRENT_BINARY_DIR}/${PROTO_BASE}.pb.cc")
      list(
        APPEND PROTOBUF_GENERATED_H_FILES
        "${CMAKE_CURRENT_BINARY_DIR}/${PROTO_BASE}.pb.h")

      SET_SOURCE_FILES_PROPERTIES(
        ${PROTOBUF_GENERATED_CC_FILES} ${PROTOBUF_GENERATED_H_FILES}
        PROPERTIES GENERATED TRUE)

    endforeach(PROTO_BASE)

    add_custom_command(
      OUTPUT ${PROTOBUF_GENERATED_CC_FILES} ${PROTOBUF_GENERATED_H_FILES}
      COMMAND  ${PROTOBUF_PROTOC_EXECUTABLE}
      ARGS --cpp_out  ${CMAKE_CURRENT_BINARY_DIR} --proto_path ${CMAKE_CURRENT_SOURCE_DIR} ${PROTO_FILES_ABS}
      DEPENDS ${PROTO_FILES_ABS}
      COMMENT "Running protocol buffer compiler on ${PROTO_FILES_ABS}"
      VERBATIM )

    set(PROTOBUF_GENERATED_CC_FILES ${PROTOBUF_GENERATED_CC_FILES} PARENT_SCOPE)
    set(PROTOBUF_GENERATED_H_FILES ${PROTOBUF_GENERATED_H_FILES} PARENT_SCOPE)
  endfunction(compile_protobuf_proto)

  # Define the add_protobuf_library function
  function(add_protobuf_library TARGET)
    if(NOT ARGN)
      message(
        SEND_ERROR
        "Error: add_protobuf_library called without any proto files (NO extension)")
      return()
    endif(NOT ARGN)

    compile_protobuf_proto(${ARGN})

    add_library(
      ${TARGET}
      ${PROTOBUF_GENERATED_H_FILES}
      ${PROTOBUF_GENERATED_CC_FILES})

    set(PROTOBUF_GENERATED_H_FILES ${PROTOBUF_GENERATED_H_FILES} PARENT_SCOPE)
  endfunction(add_protobuf_library)

ENDIF(PROTOBUF_FOUND)
