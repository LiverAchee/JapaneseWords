"""探测 Pollinations 免费通道：不同 model 参数的限速情况（调试用）。"""
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

for n, model in enumerate(sys.argv[1:] or ["", "turbo", "flux", "zimage"]):
    for k in range(2):
        q = "a cute corgi number %d%d, flat vector illustration" % (n, k)
        url = "https://image.pollinations.ai/prompt/" + urllib.parse.quote(q) + "?width=512&height=512&seed=%d" % (n * 10 + k)
        if model:
            url += "&model=" + model
        t = time.time()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=90) as r:
                d = r.read()
                print(repr(model), k, r.status, len(d), "%.1fs" % (time.time() - t), flush=True)
        except urllib.error.HTTPError as e:
            print(repr(model), k, e.code, dict(e.headers).get("Retry-After"), e.read()[:300], flush=True)
        except Exception as e:  # noqa: BLE001
            print(repr(model), k, repr(e)[:100], flush=True)
