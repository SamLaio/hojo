// converter-bridge.js
// Android ↔ JS bridge layer.
// Exposes window.BridgeAPI for calls from Android's evaluateJavascript(),
// and calls Android.* for callbacks back to the native side.

'use strict';

// Pending EPUB chunks for chunked loading
let _epubChunks = [];
let _epubTotalChunks = 0;

// Disable the dither Web Worker in headless Android WebView context.
// Android background process throttling suspends Worker threads after a few
// iterations, causing the conversion pipeline to hang waiting on promises
// that never resolve. The synchronous fallback path in applyDitheringAsync
// is reliable and fast enough (~50-200ms/page on-device).
ditherWorker = null;

// ─── CREngine Initialization ─────────────────────────────────────────────────
CREngine().then(async function(module) {
    Module = module;
    console.log('CREngine WASM loaded');

    renderer = new Module.EpubRenderer(SCREEN_WIDTH, SCREEN_HEIGHT);

    // Load default fonts from CDN
    try {
        await loadRequiredFonts();
        console.log('Fonts loaded');
    } catch(e) {
        console.warn('Font loading partial:', e.message);
    }

    wasmReady = true;
    console.log('Bridge ready');

    // Notify Android that the engine is ready
    if (typeof Android !== 'undefined') {
        Android.onReady();
    }
}).catch(function(err) {
    console.error('CREngine failed to load:', err);
    if (typeof Android !== 'undefined') {
        Android.onError('CREngine failed to load: ' + err.message);
    }
});

// ─── BridgeAPI ────────────────────────────────────────────────────────────────
window.BridgeAPI = {

    isReady: function() {
        return wasmReady;
    },

    // Apply settings from Android (JSON produced by WebViewConverter.settingsToJson).
    // All values are already in the exact format the web converter's getSettings() produces.
    applyAndroidSettings: function(settingsJson) {
        try {
            const s = JSON.parse(settingsJson);

            // Update device dimensions and renderer size first
            const rotation = s.rotation || 0;
            DEVICE_WIDTH  = s.deviceWidth  || 480;
            DEVICE_HEIGHT = s.deviceHeight || 800;
            SCREEN_WIDTH  = (rotation === 90 || rotation === 270) ? DEVICE_HEIGHT : DEVICE_WIDTH;
            SCREEN_HEIGHT = (rotation === 90 || rotation === 270) ? DEVICE_WIDTH  : DEVICE_HEIGHT;
            if (renderer) renderer.resize(SCREEN_WIDTH, SCREEN_HEIGHT);

            _androidSettings = {
                // Text
                fontSize:         s.fontSize  !== undefined ? s.fontSize  : 22,
                fontWeight:       s.fontWeight !== undefined ? s.fontWeight : 400,
                lineHeight:       s.lineHeight !== undefined ? s.lineHeight : 120,
                margin:           s.margin     !== undefined ? s.margin     : 20,
                fontFace:         s.fontFace   || 'Literata',
                textAlign:        s.textAlign  !== undefined ? s.textAlign  : -1,
                wordSpacing:      s.wordSpacing !== undefined ? s.wordSpacing : 100,
                // hyphenation is an int: 0=Off, 1=Algorithmic, 2=Dictionary
                hyphenation:      s.hyphenation !== undefined ? s.hyphenation : 2,
                hyphenationLang:  s.hyphenationLang || 'auto',
                ignoreDocMargins: s.ignoreDocMargins !== false,
                // Image
                qualityMode:      s.colorMode === 'MONOCHROME' ? 'fast' : 'hq',
                bitDepth:         s.colorMode === 'MONOCHROME' ? 1 : 2,
                enableDithering:  s.enableDithering !== false,
                ditherStrength:   s.ditherStrength !== undefined ? s.ditherStrength : 70,
                enableNegative:   s.enableNegative === true,
                // Font rendering
                fontHinting:      s.fontHinting !== undefined ? s.fontHinting : 2,
                fontAntialiasing: s.fontAntialiasing !== undefined ? s.fontAntialiasing : 2,
                // Progress bar
                enableProgressBar:   s.enableProgressBar !== false,
                progressPosition:    s.progressPosition || 'bottom',
                showProgressLine:    s.showProgressLine !== false,
                showChapterMarks:    s.showChapterMarks !== false,
                showChapterProgress: s.showChapterProgress === true,
                progressFullWidth:   s.progressFullWidth === true,
                showPageInfo:        s.showPageInfo !== false,
                showBookPercent:     s.showBookPercent !== false,
                showChapterPage:     s.showChapterPage !== false,
                showChapterPercent:  s.showChapterPercent === true,
                progressFontSize:    s.progressFontSize !== undefined ? s.progressFontSize : 14,
                progressEdgeMargin:  s.progressEdgeMargin || 0,
                progressSideMargin:  s.progressSideMargin || 0,
                // Rotation (used by generateXtcData finalizePage)
                rotation: rotation,
            };

            return 'ok';
        } catch(e) {
            return 'error: ' + e.message;
        }
    },

    // Load EPUB from a single base64 string
    loadEpubBase64: async function(base64) {
        try {
            const binary = atob(base64);
            const data = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                data[i] = binary.charCodeAt(i);
            }
            const info = await loadEpubFromData(data, 'book.epub');
            return JSON.stringify(info);
        } catch(e) {
            throw e;
        }
    },

    // Start a chunked EPUB load (for files > 1 MB)
    beginEpubChunks: function(totalChunks) {
        _epubChunks = [];
        _epubTotalChunks = totalChunks;
        return 'ok';
    },

    // Receive one chunk of a large EPUB
    addEpubChunk: function(base64Chunk, chunkIndex) {
        const binary = atob(base64Chunk);
        const chunk = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            chunk[i] = binary.charCodeAt(i);
        }
        _epubChunks[chunkIndex] = chunk;
        return 'ok';
    },

    // Finish chunked load and load into CREngine
    finishEpubChunks: async function() {
        try {
            // Reassemble chunks into one Uint8Array
            let totalLen = 0;
            for (const c of _epubChunks) totalLen += c.length;
            const data = new Uint8Array(totalLen);
            let offset = 0;
            for (const c of _epubChunks) {
                data.set(c, offset);
                offset += c.length;
            }
            _epubChunks = [];

            const info = await loadEpubFromData(data, 'book.epub');
            return JSON.stringify(info);
        } catch(e) {
            throw e;
        }
    },

    // Register a custom font from base64 data
    registerCustomFont: function(base64, filename) {
        try {
            const binary = atob(base64);
            const data = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                data[i] = binary.charCodeAt(i);
            }
            if (renderer && renderer.registerFontFromMemory) {
                const ptr = Module.allocateMemory(data.length);
                Module.HEAPU8.set(data, ptr);
                renderer.registerFontFromMemory(ptr, data.length, filename);
                Module.freeMemory(ptr);
            }
            return 'ok';
        } catch(e) {
            return 'error: ' + e.message;
        }
    },

    // Run full XTC conversion; results streamed via Android.onXtcChunk()
    startConversion: async function() {
        if (!wasmReady || !renderer) {
            if (typeof Android !== 'undefined') Android.onError('WASM not ready');
            return;
        }

        isProcessing = true;
        try {
            const xtcBuffer = await generateXtcData(function(progress, total, msg) {
                if (typeof Android !== 'undefined') {
                    Android.onProgress(Math.round(progress), Math.round(total));
                }
            });

            // Stream result back as base64 chunks (~512KB each)
            const xtcData = new Uint8Array(xtcBuffer);
            const CHUNK_SIZE = 524288; // 512 KB
            const totalChunks = Math.ceil(xtcData.length / CHUNK_SIZE);

            for (let i = 0; i < totalChunks; i++) {
                const start = i * CHUNK_SIZE;
                const end = Math.min(start + CHUNK_SIZE, xtcData.length);
                const chunk = xtcData.slice(start, end);

                // Convert chunk to base64
                let binary = '';
                for (let j = 0; j < chunk.length; j++) {
                    binary += String.fromCharCode(chunk[j]);
                }
                const base64 = btoa(binary);

                if (typeof Android !== 'undefined') {
                    Android.onXtcChunk(base64, i, i === totalChunks - 1);
                }
            }
        } catch(e) {
            console.error('Conversion failed:', e);
            if (typeof Android !== 'undefined') Android.onError(e.message || 'Conversion failed');
        } finally {
            isProcessing = false;
        }
    },
};
