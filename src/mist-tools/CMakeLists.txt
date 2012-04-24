find_package(Protobuf REQUIRED)

SET(CMAKE_INSTALL_RPATH "/opt/trend/tme/lib")
SET(CMAKE_BUILD_WITH_INSTALL_RPATH TRUE)

include_directories(
  ${PROTOBUF_INCLUDE_DIR}
  ${GENERATED_MIST_PROTOS_DIR}
  ${TME_SOURCE_DIR})

link_directories (${TME_BINARY_DIR}/mist_proto) 

add_executable(mist-encode mist_encode.cpp)
add_executable(mist-decode mist_decode.cpp)
add_executable(mist-source mist_source.cpp)
add_executable(mist-sink mist_sink.cpp)
add_executable(mist-session mist_session.cpp)

target_link_libraries(
  mist-encode
  mist_proto pthread ${PROTOBUF_LIBRARY})
target_link_libraries(
  mist-decode
  mist_proto pthread ${PROTOBUF_LIBRARY})
target_link_libraries(
  mist-source
  mist_proto pthread ${PROTOBUF_LIBRARY})
target_link_libraries(
  mist-sink
  mist_proto pthread ${PROTOBUF_LIBRARY})
target_link_libraries(
  mist-session
  mist_proto pthread ${PROTOBUF_LIBRARY})
