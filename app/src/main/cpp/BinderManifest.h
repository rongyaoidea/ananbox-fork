#ifndef ANANBOX_BINDER_MANIFEST_H
#define ANANBOX_BINDER_MANIFEST_H

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

#include <string.h>

namespace ananbox {

// BINDER_TYPE_* from uapi/linux/android/binder.h: B_PACK_CHARS('s','b','*',0x85)
// for BINDER, and likewise for the other object kinds.
enum BinderObjectType : uint32_t {
    kTypeBinder = 0x73622A85u,
    kTypeWeakBinder = 0x77622A85u,
    kTypeHandle = 0x73682A85u,
    kTypeWeakHandle = 0x77682A85u,
    kTypeFd = 0x66642A85u,
    kTypeFda = 0x66646185u,
    kTypePtr = 0x70742A85u,
};

// flat_binder_object on LP64: u32 type, u32 flags, u64 value, u64 cookie.
inline constexpr size_t kBinderObjectSize = 24;

inline const char* binderObjectTypeName(uint32_t type) {
    switch (type) {
        case kTypeBinder: return "binder";
        case kTypeWeakBinder: return "weak_binder";
        case kTypeHandle: return "handle";
        case kTypeWeakHandle: return "weak_handle";
        case kTypeFd: return "fd";
        case kTypeFda: return "fd_array";
        case kTypePtr: return "ptr";
        default: return "unknown";
    }
}

inline uint64_t fnv1a64(const uint8_t* data, size_t size) {
    uint64_t hash = 0xcbf29ce484222325ull;
    for (size_t i = 0; i < size; ++i) {
        hash ^= data[i];
        hash *= 1099511628211ull;
    }
    return hash;
}

inline std::string hex64(uint64_t value) {
    char buffer[19];
    snprintf(buffer, sizeof(buffer), "0x%016llx", static_cast<unsigned long long>(value));
    return buffer;
}

inline std::string hex16(uint64_t value) {
    char buffer[17];
    snprintf(buffer, sizeof(buffer), "%016llx", static_cast<unsigned long long>(value));
    return buffer;
}

inline std::string jsonEscape(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 2);
    for (char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buffer[7];
                    snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                    out += buffer;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

inline uint32_t readU32(const uint8_t* data, size_t size, size_t offset, bool* ok) {
    if (offset + sizeof(uint32_t) > size) {
        *ok = false;
        return 0;
    }
    uint32_t value = 0;
    memcpy(&value, data + offset, sizeof(value));
    return value;
}

inline uint64_t readU64(const uint8_t* data, size_t size, size_t offset, bool* ok) {
    if (offset + sizeof(uint64_t) > size) {
        *ok = false;
        return 0;
    }
    uint64_t value = 0;
    memcpy(&value, data + offset, sizeof(value));
    return value;
}

// Describes one dump produced by Anbox.dumpParcel:
//   int64 dataSize | int64 objectsSize | data[dataSize] | objects[objectsSize]
// `objects` holds byte offsets (into data) of flat_binder_object records.
inline std::string binderManifestJson(const uint8_t* data, size_t dataSize,
                                      const uint64_t* objects, size_t objectsSize,
                                      const std::string& source, bool valid) {
    std::string out;
    out.reserve(256 + objectsSize * 160);
    out += "{\"version\":1";
    out += ",\"source\":\"" + jsonEscape(source) + "\"";
    out += ",\"parcelFormat\":\"int64 dataSize,int64 objectsSize,uint8 data[],uint64 objects[]\"";
    out += ",\"valid\":" + std::string(valid ? "true" : "false");
    out += ",\"dataSize\":" + std::to_string(dataSize);
    out += ",\"objectsSize\":" + std::to_string(objectsSize);
    out += ",\"dataFnv1a64\":\"" + hex16(fnv1a64(data, dataSize)) + "\"";
    out += ",\"objectsFnv1a64\":\"" +
           hex16(fnv1a64(reinterpret_cast<const uint8_t*>(objects), objectsSize * sizeof(uint64_t))) + "\"";
    out += ",\"objects\":[";
    for (size_t i = 0; i < objectsSize; ++i) {
        if (i != 0) out += ",";
        uint64_t offset = objects[i];
        bool ok = offset <= dataSize;
        uint32_t type = readU32(data, dataSize, static_cast<size_t>(offset), &ok);
        uint32_t flags = readU32(data, dataSize, static_cast<size_t>(offset) + 4, &ok);
        uint64_t value = readU64(data, dataSize, static_cast<size_t>(offset) + 8, &ok);
        uint64_t cookie = readU64(data, dataSize, static_cast<size_t>(offset) + 16, &ok);
        if (ok && offset + kBinderObjectSize > dataSize) ok = false;
        out += "{\"index\":" + std::to_string(i);
        out += ",\"offset\":" + std::to_string(offset);
        out += ",\"valid\":" + std::string(ok ? "true" : "false");
        out += ",\"type\":\"" + std::string(ok ? binderObjectTypeName(type) : "invalid") + "\"";
        out += ",\"typeId\":" + std::to_string(ok ? type : 0);
        out += ",\"flags\":" + std::to_string(ok ? flags : 0);
        out += ",\"value\":\"" + hex64(ok ? value : 0) + "\"";
        out += ",\"cookie\":\"" + hex64(ok ? cookie : 0) + "\"";
        // A locally-created binder is what the host injected as placeholder;
        // remote references show up as handles.
        out += ",\"placeholder\":" +
               std::string(ok && (type == kTypeBinder || type == kTypeWeakBinder) ? "true" : "false");
        out += "}";
    }
    out += "]}";
    return out;
}

}  // namespace ananbox

#endif  // ANANBOX_BINDER_MANIFEST_H
