#pragma once

/**
 * HTTP MJPEG servers, one independent libhv instance per camera slot so each
 * camera can be reached on its own port:
 *
 *   camera 0 -> http://<ip>:<port0>/stream.mjpg
 *   camera 1 -> http://<ip>:<port1>/stream.mjpg
 */
namespace http_mjpeg_server {

/** Server slots this module supports (index 0 / 1). */
constexpr int kMaxServers = 2;

/** Start (or restart) the server for camera `cam` on 0.0.0.0:port. 0 = ok. */
int start(int cam, int port);

void stop(int cam);

bool is_running(int cam);

int port(int cam);

int client_count(int cam);

}  // namespace http_mjpeg_server
