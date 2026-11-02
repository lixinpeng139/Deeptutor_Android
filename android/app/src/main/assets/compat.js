// Injected at document start by MainActivity. The DeepTutor web UI targets
// current Chromium; many Android ROMs ship an older system WebView that cannot
// be updated without Google Play. Each shim only fills in a missing feature.
(function () {
  "use strict";
  if (window.__deeptutorCompat) return;
  window.__deeptutorCompat = true;

  function define(target, name, value) {
    if (!(name in target)) {
      Object.defineProperty(target, name, { value: value, writable: true, configurable: true });
    }
  }

  // Chrome < 125 rejects relative and http(s) WebSocket URLs.
  var NativeWebSocket = window.WebSocket;
  try {
    new NativeWebSocket("/__deeptutor_probe__").close();
  } catch (error) {
    var resolve = function (url) {
      var parsed = new URL(String(url), window.location.href);
      if (parsed.protocol === "http:") parsed.protocol = "ws:";
      else if (parsed.protocol === "https:") parsed.protocol = "wss:";
      return parsed.toString();
    };
    var Patched = function WebSocket(url, protocols) {
      return protocols === undefined
        ? new NativeWebSocket(resolve(url))
        : new NativeWebSocket(resolve(url), protocols);
    };
    Patched.prototype = NativeWebSocket.prototype;
    ["CONNECTING", "OPEN", "CLOSING", "CLOSED"].forEach(function (key) {
      Patched[key] = NativeWebSocket[key];
    });
    window.WebSocket = Patched;
  }

  // Chrome 120 / 126
  define(URL, "canParse", function (url, base) {
    try { new URL(url, base); return true; } catch (e) { return false; }
  });
  define(URL, "parse", function (url, base) {
    try { return new URL(url, base); } catch (e) { return null; }
  });

  // Chrome 122: iterator helpers on Map/Set/Array iterators and generators.
  var IteratorProto = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
  if (IteratorProto && !("map" in IteratorProto)) {
    var helpers = {
      map: function (fn) {
        var it = this, i = 0;
        return (function* () { for (var v of { [Symbol.iterator]: function () { return it; } }) yield fn(v, i++); })();
      },
      filter: function (fn) {
        var it = this, i = 0;
        return (function* () { for (var v of { [Symbol.iterator]: function () { return it; } }) if (fn(v, i++)) yield v; })();
      },
      take: function (n) {
        var it = this;
        return (function* () { if (n <= 0) return; var i = 0; for (var v of { [Symbol.iterator]: function () { return it; } }) { yield v; if (++i >= n) return; } })();
      },
      drop: function (n) {
        var it = this;
        return (function* () { var i = 0; for (var v of { [Symbol.iterator]: function () { return it; } }) if (i++ >= n) yield v; })();
      },
      flatMap: function (fn) {
        var it = this, i = 0;
        return (function* () { for (var v of { [Symbol.iterator]: function () { return it; } }) yield* fn(v, i++); })();
      },
      toArray: function () { var out = []; for (var r = this.next(); !r.done; r = this.next()) out.push(r.value); return out; },
      forEach: function (fn) { var i = 0; for (var r = this.next(); !r.done; r = this.next()) fn(r.value, i++); },
      some: function (fn) { var i = 0; for (var r = this.next(); !r.done; r = this.next()) if (fn(r.value, i++)) return true; return false; },
      every: function (fn) { var i = 0; for (var r = this.next(); !r.done; r = this.next()) if (!fn(r.value, i++)) return false; return true; },
      find: function (fn) { var i = 0; for (var r = this.next(); !r.done; r = this.next()) if (fn(r.value, i++)) return r.value; return undefined; },
      reduce: function (fn, initial) {
        var r = this.next(), acc, i = 0;
        if (arguments.length > 1) acc = initial;
        else { if (r.done) throw new TypeError("Reduce of empty iterator with no initial value"); acc = r.value; r = this.next(); i = 1; }
        for (; !r.done; r = this.next()) acc = fn(acc, r.value, i++);
        return acc;
      },
    };
    Object.keys(helpers).forEach(function (name) { define(IteratorProto, name, helpers[name]); });
  }

  // Chrome 117
  define(Object, "groupBy", function (items, callback) {
    var result = Object.create(null);
    var index = 0;
    for (var item of items) {
      var key = callback(item, index++);
      (result[key] || (result[key] = [])).push(item);
    }
    return result;
  });
  define(Map, "groupBy", function (items, callback) {
    var result = new Map();
    var index = 0;
    for (var item of items) {
      var key = callback(item, index++);
      if (!result.has(key)) result.set(key, []);
      result.get(key).push(item);
    }
    return result;
  });

  // Chrome 128
  define(Promise, "try", function (fn) {
    var args = Array.prototype.slice.call(arguments, 1);
    return new this(function (resolve) { resolve(fn.apply(undefined, args)); });
  });

  // Chrome 119
  define(Promise, "withResolvers", function () {
    var resolve, reject;
    var promise = new this(function (res, rej) { resolve = res; reject = rej; });
    return { promise: promise, resolve: resolve, reject: reject };
  });

  // Chrome 121
  define(Array, "fromAsync", async function (items, mapper, thisArg) {
    var out = [];
    var index = 0;
    if (items != null && typeof items[Symbol.asyncIterator] === "function") {
      for await (var value of items) out.push(mapper ? await mapper.call(thisArg, value, index++) : value);
    } else {
      for (var entry of Array.from(items)) {
        var awaited = await entry;
        out.push(mapper ? await mapper.call(thisArg, awaited, index++) : awaited);
      }
    }
    return out;
  });

  // Chrome 110
  var arrays = [Array.prototype];
  ["Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array",
    "Uint32Array", "Float32Array", "Float64Array"].forEach(function (name) {
    if (window[name]) arrays.push(window[name].prototype);
  });
  arrays.forEach(function (proto) {
    define(proto, "toSorted", function (compare) { return Array.prototype.slice.call(this).sort(compare); });
    define(proto, "toReversed", function () { return Array.prototype.slice.call(this).reverse(); });
    define(proto, "with", function (index, value) {
      var copy = Array.prototype.slice.call(this);
      var i = index < 0 ? copy.length + index : index;
      if (i < 0 || i >= copy.length) throw new RangeError("Invalid index");
      copy[i] = value;
      return copy;
    });
  });
  define(Array.prototype, "toSpliced", function () {
    var copy = this.slice();
    copy.splice.apply(copy, arguments);
    return copy;
  });

  // Chrome 122
  var SetProto = Set.prototype;
  function keysOf(other) { return other instanceof Set ? other : new Set(other.keys()); }
  define(SetProto, "union", function (other) {
    var out = new Set(this);
    keysOf(other).forEach(function (v) { out.add(v); });
    return out;
  });
  define(SetProto, "intersection", function (other) {
    var o = keysOf(other);
    var out = new Set();
    this.forEach(function (v) { if (o.has(v)) out.add(v); });
    return out;
  });
  define(SetProto, "difference", function (other) {
    var o = keysOf(other);
    var out = new Set();
    this.forEach(function (v) { if (!o.has(v)) out.add(v); });
    return out;
  });
  define(SetProto, "symmetricDifference", function (other) {
    var o = keysOf(other);
    var out = new Set(this);
    o.forEach(function (v) { if (out.has(v)) out.delete(v); else out.add(v); });
    return out;
  });
  define(SetProto, "isSubsetOf", function (other) {
    var o = keysOf(other);
    for (var v of this) if (!o.has(v)) return false;
    return true;
  });
  define(SetProto, "isSupersetOf", function (other) {
    for (var v of keysOf(other)) if (!this.has(v)) return false;
    return true;
  });
  define(SetProto, "isDisjointFrom", function (other) {
    var o = keysOf(other);
    for (var v of this) if (o.has(v)) return false;
    return true;
  });

  // Chrome 111
  define(String.prototype, "isWellFormed", function () {
    return !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(this);
  });
  define(String.prototype, "toWellFormed", function () {
    return String(this).replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/g,
      function (m) { return m.length === 2 ? m[0] + "�" : "�"; });
  });

  // Chrome 116 (AbortSignal.any) / 124 (AbortSignal.timeout exists since 103)
  if (window.AbortSignal) {
    define(AbortSignal, "any", function (signals) {
      var controller = new AbortController();
      for (var signal of signals) {
        if (signal.aborted) { controller.abort(signal.reason); return controller.signal; }
        signal.addEventListener("abort", function () { controller.abort(this.reason); }, { once: true });
      }
      return controller.signal;
    });
  }

  // Chrome 119: structuredClone exists since 98; requestIdleCallback since 47.
})();
