#include "BinderManifest.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using ananbox::binderManifestJson;
using ananbox::fnv1a64;
using ananbox::hex16;
using ananbox::kTypeBinder;
using ananbox::kTypeHandle;

static void writeObject(std::vector<uint8_t>& data, size_t offset, uint32_t type,
                        uint32_t flags, uint64_t value, uint64_t cookie) {
    assert(offset + 24 <= data.size());
    memcpy(data.data() + offset, &type, sizeof(type));
    memcpy(data.data() + offset + 4, &flags, sizeof(flags));
    memcpy(data.data() + offset + 8, &value, sizeof(value));
    memcpy(data.data() + offset + 16, &cookie, sizeof(cookie));
}

static bool contains(const std::string& haystack, const std::string& needle) {
    return haystack.find(needle) != std::string::npos;
}

int main() {
    assert(hex16(fnv1a64(nullptr, 0)) == "cbf29ce484222325");

    std::vector<uint8_t> data(128, 0);
    writeObject(data, 16, kTypeBinder, 0, 0x0000007f12345678ull, 0xdeadbeefcafeull);
    writeObject(data, 64, kTypeHandle, 0, 42ull, 0ull);
    std::vector<uint64_t> objects = {16, 64};

    std::string manifest =
            binderManifestJson(data.data(), data.size(), objects.data(), objects.size(), "localBroadcastIntent", true);

    assert(contains(manifest, "\"version\":1"));
    assert(contains(manifest, "\"source\":\"localBroadcastIntent\""));
    assert(contains(manifest, "\"valid\":true"));
    assert(contains(manifest, "\"dataSize\":128"));
    assert(contains(manifest, "\"objectsSize\":2"));

    size_t first = manifest.find("\"index\":0");
    size_t second = manifest.find("\"index\":1");
    assert(first != std::string::npos);
    assert(second != std::string::npos);
    std::string firstObject = manifest.substr(first, second - first);
    std::string secondObject = manifest.substr(second);

    assert(contains(firstObject, "\"offset\":16"));
    assert(contains(firstObject, "\"type\":\"binder\""));
    assert(contains(firstObject, "\"placeholder\":true"));
    assert(contains(firstObject, "\"value\":\"0x0000007f12345678\""));

    assert(contains(secondObject, "\"offset\":64"));
    assert(contains(secondObject, "\"type\":\"handle\""));
    assert(contains(secondObject, "\"placeholder\":false"));
    assert(contains(secondObject, "\"value\":\"0x000000000000002a\""));

    std::string again =
            binderManifestJson(data.data(), data.size(), objects.data(), objects.size(), "localBroadcastIntent", true);
    assert(manifest == again);

    std::vector<uint64_t> truncated = {120};
    std::string bad = binderManifestJson(data.data(), data.size(), truncated.data(), truncated.size(), "bad", false);
    assert(contains(bad, "\"valid\":false"));
    assert(contains(bad, "\"type\":\"invalid\""));
    assert(contains(bad, "\"placeholder\":false"));

    std::vector<uint64_t> outOfRange = {4096};
    std::string far = binderManifestJson(data.data(), data.size(), outOfRange.data(), outOfRange.size(), "far", false);
    assert(contains(far, "\"type\":\"invalid\""));

    std::string quoted = binderManifestJson(data.data(), data.size(), nullptr, 0, "a\"b\\c", true);
    assert(contains(quoted, "\"source\":\"a\\\"b\\\\c\""));
    assert(contains(quoted, "\"objects\":[]"));

    printf("binder_manifest_test: OK\n");
    return 0;
}
