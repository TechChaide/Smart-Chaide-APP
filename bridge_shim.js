// jsBridge shim — reimplementación fiel del puente nativo Android (Android4Js /
// TopicManager) del APK real, reversada por decompilación (REA/JADX).
// Protocolo exacto verificado en com.sleepace.hrbrid.topic.TopicManager:
//  JS -> nativo: window.android.dataReceived( base64(JSON{oper,payload:{topic,msgID,data}}) )
//  nativo -> JS: window.dataReceived( base64(JSON{payload:{topic,msgID,status,data}}) )
//  topic: "request/x" -> "response/x" ; "post/x" -> "ack/x"  (TopicManager.getTopic)
(function () {
  var PASSWORD = "9KO7ugCxAcbDifox"; // com.sleepace.hrbrid.topic.TopicManager.PASSWORD

  function b64encodeUtf8(str) {
    return btoa(unescape(encodeURIComponent(str)));
  }
  function b64decodeUtf8(str) {
    return decodeURIComponent(escape(atob(str)));
  }

  // MD5 (RFC1321), compacto — necesario porque StringUtil.md5 usa MessageDigest MD5.
  function md5(s) {
    function rl(n, c) { return (n << c) | (n >>> (32 - c)); }
    function aa(a, b) { var l = (a & 0xFFFF) + (b & 0xFFFF), m = (a >> 16) + (b >> 16) + (l >> 16); return (m << 16) | (l & 0xFFFF); }
    function cmn(q, a, b, x, s, t) { return aa(rl(aa(aa(a, q), aa(x, t)), s), b); }
    function ff(a, b, c, d, x, s, t) { return cmn((b & c) | (~b & d), a, b, x, s, t); }
    function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
    function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
    function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }
    function toUtf8Bytes(str) {
      var u = unescape(encodeURIComponent(str)), a = [];
      for (var i = 0; i < u.length; i++) a.push(u.charCodeAt(i));
      return a;
    }
    function bytesToWords(bytes) {
      var words = [];
      for (var i = 0; i < bytes.length * 8; i += 8) words[i >> 5] |= (bytes[i / 8] & 0xFF) << (i % 32);
      return words;
    }
    var bytes = toUtf8Bytes(s), bitLen = bytes.length * 8;
    var x = bytesToWords(bytes);
    x[bitLen >> 5] |= 0x80 << (bitLen % 32);
    x[(((bitLen + 64) >>> 9) << 4) + 14] = bitLen;
    var a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;
    for (var i2 = 0; i2 < x.length; i2 += 16) {
      var oa = a, ob = b, oc = c, od = d;
      a = ff(a, b, c, d, x[i2 + 0], 7, -680876936); d = ff(d, a, b, c, x[i2 + 1], 12, -389564586);
      c = ff(c, d, a, b, x[i2 + 2], 17, 606105819); b = ff(b, c, d, a, x[i2 + 3], 22, -1044525330);
      a = ff(a, b, c, d, x[i2 + 4], 7, -176418897); d = ff(d, a, b, c, x[i2 + 5], 12, 1200080426);
      c = ff(c, d, a, b, x[i2 + 6], 17, -1473231341); b = ff(b, c, d, a, x[i2 + 7], 22, -45705983);
      a = ff(a, b, c, d, x[i2 + 8], 7, 1770035416); d = ff(d, a, b, c, x[i2 + 9], 12, -1958414417);
      c = ff(c, d, a, b, x[i2 + 10], 17, -42063); b = ff(b, c, d, a, x[i2 + 11], 22, -1990404162);
      a = ff(a, b, c, d, x[i2 + 12], 7, 1804603682); d = ff(d, a, b, c, x[i2 + 13], 12, -40341101);
      c = ff(c, d, a, b, x[i2 + 14], 17, -1502002290); b = ff(b, c, d, a, x[i2 + 15], 22, 1236535329);
      a = gg(a, b, c, d, x[i2 + 1], 5, -165796510); d = gg(d, a, b, c, x[i2 + 6], 9, -1069501632);
      c = gg(c, d, a, b, x[i2 + 11], 14, 643717713); b = gg(b, c, d, a, x[i2 + 0], 20, -373897302);
      a = gg(a, b, c, d, x[i2 + 5], 5, -701558691); d = gg(d, a, b, c, x[i2 + 10], 9, 38016083);
      c = gg(c, d, a, b, x[i2 + 15], 14, -660478335); b = gg(b, c, d, a, x[i2 + 4], 20, -405537848);
      a = gg(a, b, c, d, x[i2 + 9], 5, 568446438); d = gg(d, a, b, c, x[i2 + 14], 9, -1019803690);
      c = gg(c, d, a, b, x[i2 + 3], 14, -187363961); b = gg(b, c, d, a, x[i2 + 8], 20, 1163531501);
      a = gg(a, b, c, d, x[i2 + 13], 5, -1444681467); d = gg(d, a, b, c, x[i2 + 2], 9, -51403784);
      c = gg(c, d, a, b, x[i2 + 7], 14, 1735328473); b = gg(b, c, d, a, x[i2 + 12], 20, -1926607734);
      a = hh(a, b, c, d, x[i2 + 5], 4, -378558); d = hh(d, a, b, c, x[i2 + 8], 11, -2022574463);
      c = hh(c, d, a, b, x[i2 + 11], 16, 1839030562); b = hh(b, c, d, a, x[i2 + 14], 23, -35309556);
      a = hh(a, b, c, d, x[i2 + 1], 4, -1530992060); d = hh(d, a, b, c, x[i2 + 4], 11, 1272893353);
      c = hh(c, d, a, b, x[i2 + 7], 16, -155497632); b = hh(b, c, d, a, x[i2 + 10], 23, -1094730640);
      a = hh(a, b, c, d, x[i2 + 13], 4, 681279174); d = hh(d, a, b, c, x[i2 + 0], 11, -358537222);
      c = hh(c, d, a, b, x[i2 + 3], 16, -722521979); b = hh(b, c, d, a, x[i2 + 6], 23, 76029189);
      a = hh(a, b, c, d, x[i2 + 9], 4, -640364487); d = hh(d, a, b, c, x[i2 + 12], 11, -421815835);
      c = hh(c, d, a, b, x[i2 + 15], 16, 530742520); b = hh(b, c, d, a, x[i2 + 2], 23, -995338651);
      a = ii(a, b, c, d, x[i2 + 0], 6, -198630844); d = ii(d, a, b, c, x[i2 + 7], 10, 1126891415);
      c = ii(c, d, a, b, x[i2 + 14], 15, -1416354905); b = ii(b, c, d, a, x[i2 + 5], 21, -57434055);
      a = ii(a, b, c, d, x[i2 + 12], 6, 1700485571); d = ii(d, a, b, c, x[i2 + 3], 10, -1894986606);
      c = ii(c, d, a, b, x[i2 + 10], 15, -1051523); b = ii(b, c, d, a, x[i2 + 1], 21, -2054922799);
      a = ii(a, b, c, d, x[i2 + 8], 6, 1873313359); d = ii(d, a, b, c, x[i2 + 15], 10, -30611744);
      c = ii(c, d, a, b, x[i2 + 6], 15, -1560198380); b = ii(b, c, d, a, x[i2 + 13], 21, 1309151649);
      a = ii(a, b, c, d, x[i2 + 4], 6, -145523070); d = ii(d, a, b, c, x[i2 + 11], 10, -1120210379);
      c = ii(c, d, a, b, x[i2 + 2], 15, 718787259); b = ii(b, c, d, a, x[i2 + 9], 21, -343485551);
      a = aa(a, oa); b = aa(b, ob); c = aa(c, oc); d = aa(d, od);
    }
    function toHex(n) {
      var s2 = "", b2;
      for (var j = 0; j < 4; j++) { b2 = (n >> (j * 8)) & 255; s2 += (b2 < 16 ? "0" : "") + b2.toString(16); }
      return s2;
    }
    return toHex(a) + toHex(b) + toHex(c) + toHex(d);
  }

  function respTopic(topic) {
    var i = topic.indexOf("/");
    var rest = topic.substring(i); // incluye la barra
    if (topic.indexOf("request") === 0) return "response" + rest;
    if (topic.indexOf("post") === 0) return "ack" + rest;
    return topic;
  }

  function send(topic, msgID, status, data) {
    var payload = { payload: { topic: topic, msgID: msgID, status: status, data: data } };
    var b64 = b64encodeUtf8(JSON.stringify(payload));
    if (window.dataReceived) window.dataReceived(b64);
  }

  window.android = {
    dataReceived: function (b64msg) {
      try {
        var msg = JSON.parse(b64decodeUtf8(b64msg));
        if (!msg || msg.oper !== "publish" || !msg.payload) return;
        var topic = msg.payload.topic, msgID = msg.payload.msgID, data = msg.payload.data;
        var rt = respTopic(topic);

        if (topic === "request/data/verify/login") {
          // El JS manda verifyLogin({data: username}) -> payload.data = {data: username} (anidado).
          var loginData = data && typeof data === "object" ? data.data : data;
          var ts = String(Date.now());
          var S_MNJZI = b64encodeUtf8(ts);
          var S_QJXIQ = md5(PASSWORD + loginData + ts + PASSWORD);
          send(rt, msgID, 0, { S_MNJZI: S_MNJZI, S_QJXIQ: S_QJXIQ });
          return;
        }
        if (topic === "request/data/verify/securityCode") {
          var codeData = data && typeof data === "object" ? data.data : data;
          var ts2 = String(Date.now());
          send(rt, msgID, 0, {
            S_MNJZI: b64encodeUtf8(ts2),
            S_QJXIQ: md5(PASSWORD + codeData + ts2 + PASSWORD),
            S_CTXOQ: b64encodeUtf8(codeData),
          });
          return;
        }
        // Respuestas genéricas "OK" para el resto (no bloquean el flujo de login/datos).
        send(rt, msgID, 0, {});
      } catch (e) {
        console.error("bridge_shim error", e);
      }
    },
  };
  window.navigator.useWkWebView = false; // fuerza la rama Android del vendor.js (window.android.dataReceived)
  console.log("[bridge_shim] jsBridge nativo reimplementado e instalado.");
})();
