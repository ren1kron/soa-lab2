<?php
// Published with the two configured ports and the public certificates beside this file.
header('Cache-Control: no-store');

function gatewayError($status, $message) {
    http_response_code($status);
    header('Content-Type: application/xml; charset=UTF-8');
    echo '<error><code>PROXY_ERROR</code><message>' . $message . '</message><details/></error>';
    exit;
}

$path = explode('?', $_SERVER['REQUEST_URI'], 2)[0];
if (!preg_match('~/api\.php/(organizations|manager)(/(organizations|employees|orgmanager)(?:/[A-Za-z0-9_-]+)*)$~D', $path, $route)) {
    gatewayError(404, 'Unknown API route');
}
$manager = $route[1] === 'manager';
if ($manager !== ($route[3] === 'orgmanager')) {
    gatewayError(404, 'Unknown API route');
}
$method = $_SERVER['REQUEST_METHOD'];
if (!in_array($method, ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS'], true)) {
    header('Allow: GET, HEAD, POST, PUT, DELETE, OPTIONS');
    gatewayError(405, 'Unsupported method');
}

$port = $manager ? '@MANAGER_PORT@' : '@ORGANIZATIONS_PORT@';
$query = $_SERVER['QUERY_STRING'] ?? '';
$url = 'https://127.0.0.1:' . $port . $route[2] . ($query === '' ? '' : '?' . $query);
$body = file_get_contents('php://input');
if ($body === false) {
    gatewayError(400, 'Could not read request');
}
$headers = ['Connection: close'];
foreach (['HTTP_ACCEPT' => 'Accept', 'CONTENT_TYPE' => 'Content-Type', 'HTTP_CONTENT_ENCODING' => 'Content-Encoding'] as $key => $name) {
    if (isset($_SERVER[$key])) {
        $headers[] = $name . ': ' . $_SERVER[$key];
    }
}

$context = stream_context_create([
    'http' => [
        'method' => $method, 'header' => $headers, 'content' => $body,
        'ignore_errors' => true, 'follow_location' => 0, 'timeout' => 55,
    ],
    'ssl' => [
        'cafile' => __DIR__ . '/' . $route[1] . '.crt', 'peer_name' => 'localhost',
        'verify_peer' => true, 'verify_peer_name' => true,
    ],
]);
$upstream = @fopen($url, 'rb', false, $context);
if ($upstream === false) {
    gatewayError(502, 'Could not reach the HTTPS service');
}
$reply = stream_get_meta_data($upstream)['wrapper_data'];
$content = @stream_get_contents($upstream);
$timedOut = stream_get_meta_data($upstream)['timed_out'];
fclose($upstream);
if ($timedOut || $content === false || !preg_match('~^HTTP/\S+ (\d{3})~', $reply[0], $status)) {
    gatewayError(502, 'Incomplete HTTPS response');
}
foreach ($reply as $line) {
    if ($method !== 'HEAD' && preg_match('/^Content-Length:\s*(\d+)$/i', $line, $length) && strlen($content) !== (int)$length[1]) {
        gatewayError(502, 'Incomplete HTTPS response');
    }
}
http_response_code((int)$status[1]);
foreach ($reply as $line) {
    if (preg_match('/^(Content-Type|X-Total-Count|X-Page|X-Page-Size|Retry-After|Allow):/i', $line)) {
        header($line);
    }
}
echo $content;
