#include "http_mjpeg_server.h"

#include "frame_pipeline.h"
#include "log.h"

#include "hv/HttpServer.h"
#include "hv/hthread.h"

#include <atomic>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace http_mjpeg_server {
namespace {

constexpr int kMax = kMaxServers;
constexpr const char *kBoundary = "frame";
constexpr const char *kStreamPath = "/stream.mjpg";

struct ServerSlot {
    std::mutex mu;
    std::unique_ptr<hv::HttpService> service;
    std::unique_ptr<hv::HttpServer> server;
    std::atomic<bool> running{false};
    std::atomic<int> port{0};
    std::atomic<int> clients{0};
};

ServerSlot g_slots[kMax];

bool valid_cam(int cam) {
    return cam >= 0 && cam < kMax;
}

std::string camera_label(int cam) {
    return std::string("UVC 立传 · 摄像头 ") + std::to_string(cam + 1);
}

std::string index_html(int cam) {
    std::string title = camera_label(cam);
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
           "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
           "<title>" + title + "</title></head><body style=\"margin:0;background:#111;color:#eee;"
           "font-family:sans-serif;text-align:center\">"
           "<h1 style=\"margin:12px\">" + title + "</h1>"
           "<p style=\"opacity:.7;margin:0 12px 12px;font-size:14px\">EasyUVCStreamer</p>"
           "<img src=\"" + kStreamPath + "\" style=\"max-width:100%;height:auto\" alt=\"stream\">"
           "</body></html>";
}

void write_mjpeg_stream(const HttpContextPtr &ctx, int cam) {
    auto writer = ctx->writer;

    writer->Begin();
    writer->WriteStatus(HTTP_STATUS_OK);
    writer->WriteHeader("Content-Type",
                        "multipart/x-mixed-replace; boundary=frame");
    writer->WriteHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    writer->WriteHeader("Pragma", "no-cache");
    writer->WriteHeader("Access-Control-Allow-Origin", "*");
    writer->EndHeaders();

    // Registered only once the response headers exist, so a refused connection
    // never bumps the subscriber count (which drives frame copying in native).
    frame_pipeline::http_subscriber_add(cam);
    g_slots[cam].clients.fetch_add(1, std::memory_order_relaxed);

    uint64_t last_seq = 0;
    std::vector<uint8_t> jpeg;
    jpeg.reserve(128 * 1024);

    while (writer->isConnected()) {
        if (!writer->isWriteComplete()) {
            hv_delay(1);
            continue;
        }
        if (!frame_pipeline::copy_http_frame_if_newer(cam, last_seq, jpeg)) {
            hv_delay(5);
            continue;
        }

        std::ostringstream hdr;
        hdr << "--" << kBoundary << "\r\n"
            << "Content-Type: image/jpeg\r\n"
            << "Content-Length: " << jpeg.size() << "\r\n"
            << "\r\n";
        const std::string header = hdr.str();

        if (writer->WriteBody(header) < 0) break;
        if (writer->WriteBody(reinterpret_cast<const char *>(jpeg.data()),
                              static_cast<int>(jpeg.size())) < 0) {
            break;
        }
        if (writer->WriteBody("\r\n", 2) < 0) break;
    }

    frame_pipeline::http_subscriber_remove(cam);
    g_slots[cam].clients.fetch_sub(1, std::memory_order_relaxed);

    if (writer->isConnected()) {
        writer->close();
    }
}

void setup_routes(hv::HttpService &service, int cam) {
    service.GET("/", [cam](HttpRequest *req, HttpResponse *resp) {
        (void) req;
        resp->content_type = TEXT_HTML;
        resp->body = index_html(cam);
        return 200;
    });

    service.GET("/status", [cam](HttpRequest *req, HttpResponse *resp) {
        (void) req;
        resp->content_type = APPLICATION_JSON;
        ServerSlot &slot = g_slots[cam];
        std::ostringstream ss;
        ss << "{\"camera\":" << cam
           << ",\"running\":" << (slot.running.load() ? "true" : "false")
           << ",\"port\":" << slot.port.load()
           << ",\"clients\":" << slot.clients.load()
           << ",\"subscribers\":" << frame_pipeline::http_subscriber_count(cam)
           << ",\"stream\":\"" << kStreamPath << "\"}";
        resp->body = ss.str();
        return 200;
    });

    service.GET(kStreamPath, [cam](const HttpContextPtr &ctx) {
        std::thread([ctx, cam]() {
            write_mjpeg_stream(ctx, cam);
        }).detach();
        return HTTP_STATUS_UNFINISHED;
    });
}

void stop_slot_locked(ServerSlot &slot) {
    if (slot.server) {
        slot.server->stop();
        slot.server.reset();
    }
    slot.service.reset();
    slot.running.store(false);
    slot.port.store(0);
}

}  // namespace

int start(int cam, int port) {
    if (!valid_cam(cam)) {
        LOGE("http_mjpeg_server: invalid camera %d", cam);
        return -1;
    }
    if (port <= 0 || port > 65535) {
        LOGE("http_mjpeg_server: invalid port %d (cam %d)", port, cam);
        return -1;
    }

    ServerSlot &slot = g_slots[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    if (slot.running.load() && slot.port.load() == port && slot.server) {
        LOGI("http_mjpeg_server: cam %d already running on %d", cam, port);
        return 0;
    }

    if (slot.server) {
        stop_slot_locked(slot);
    }

    auto service = std::make_unique<hv::HttpService>();
    setup_routes(*service, cam);

    auto server = std::make_unique<hv::HttpServer>();
    server->registerHttpService(service.get());
    server->setHost("0.0.0.0");
    server->setPort(port);
    server->setThreadNum(1);

    int ret = server->start();
    if (ret != 0) {
        LOGE("http_mjpeg_server: start failed cam=%d port=%d ret=%d", cam, port, ret);
        return ret;
    }

    slot.service = std::move(service);
    slot.server = std::move(server);
    slot.port.store(port);
    slot.running.store(true);
    LOGI("http_mjpeg_server: cam %d listening on 0.0.0.0:%d", cam, port);
    return 0;
}

void stop(int cam) {
    if (!valid_cam(cam)) return;
    ServerSlot &slot = g_slots[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    stop_slot_locked(slot);
    LOGI("http_mjpeg_server: cam %d stopped", cam);
}

bool is_running(int cam) {
    return valid_cam(cam) && g_slots[cam].running.load(std::memory_order_relaxed);
}

int port(int cam) {
    return valid_cam(cam) ? g_slots[cam].port.load(std::memory_order_relaxed) : 0;
}

int client_count(int cam) {
    return valid_cam(cam) ? g_slots[cam].clients.load(std::memory_order_relaxed) : 0;
}

}  // namespace http_mjpeg_server
