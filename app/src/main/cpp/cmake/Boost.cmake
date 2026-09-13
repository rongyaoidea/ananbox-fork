set(BOOST_VER 1.83.0)
set(BOOST_ARCHIVE "${CMAKE_SOURCE_DIR}/boost-${BOOST_VER}.tar.xz")
set(BOOST_EXTRACTED_DIR "${CMAKE_SOURCE_DIR}/external/boost-${BOOST_VER}")
set(BOOST_DIR "${CMAKE_SOURCE_DIR}/boost")

# Boost is shared by every ABI configuration of the same build, so the
# download/extract step must be serialized and use absolute paths (AGP runs
# CMake with the source directory as working directory, which broke the
# original relative file(RENAME)).
file(LOCK "${CMAKE_SOURCE_DIR}/.boost-prepare.lock" TIMEOUT 900 RESULT_VARIABLE BOOST_LOCK_RESULT)
if(BOOST_LOCK_RESULT)
    message(FATAL_ERROR "Failed to acquire Boost preparation lock: ${BOOST_LOCK_RESULT}")
endif()

if(NOT EXISTS "${BOOST_DIR}")
    if(NOT EXISTS "${BOOST_EXTRACTED_DIR}")
        message(STATUS "Downloading Boost ${BOOST_VER} ......")
        file(
                DOWNLOAD "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VER}/boost-${BOOST_VER}.tar.xz" "${BOOST_ARCHIVE}"
                EXPECTED_HASH SHA256=c5a0688e1f0c05f354bbd0b32244d36085d9ffc9f932e8a18983a9908096f614
                SHOW_PROGRESS
        )
        message(STATUS "Extracting Boost ${BOOST_VER} ......")
        file(ARCHIVE_EXTRACT INPUT "${BOOST_ARCHIVE}"
                DESTINATION "${CMAKE_SOURCE_DIR}/external"
                )
    endif()
    file(RENAME "${BOOST_EXTRACTED_DIR}" "${BOOST_DIR}")
endif()

file(LOCK "${CMAKE_SOURCE_DIR}/.boost-prepare.lock" RELEASE)

set(BOOST_INCLUDE_LIBRARIES
        algorithm
        crc
        date_time
        dll
        interprocess
        range
        regex
        scope_exit
        signals2
        utility
        uuid
        #locale
        asio
        filesystem
        log
        #log_setup
        serialization
        system
        thread
        program_options
        )

add_subdirectory(boost EXCLUDE_FROM_ALL)
