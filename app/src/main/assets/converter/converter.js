// converter.js
// Core XTC conversion logic from x4converter.rho.sh
// DOM-specific UI code removed; settings driven by _androidSettings global.
// Loaded by converter-bridge.html after crengine.js.

'use strict';

// ─── Device dimensions ───────────────────────────────────────────────────────
let DEVICE_WIDTH = 480;
let DEVICE_HEIGHT = 800;
let SCREEN_WIDTH = 480;
let SCREEN_HEIGHT = 800;

// ─── CREngine state ──────────────────────────────────────────────────────────
let Module = null;
let renderer = null;
let wasmReady = false;
let metadata = {};
let isProcessing = false;
let currentToc = [];

// ─── Dither worker ───────────────────────────────────────────────────────────
let ditherWorker = null;
let ditherCallbacks = new Map();
let ditherJobId = 0;

try {
    ditherWorker = new Worker('dither-worker.js');
    ditherWorker.onmessage = function(e) {
        const { imageData, id } = e.data;
        const callback = ditherCallbacks.get(id);
        if (callback) {
            callback(new Uint8ClampedArray(imageData));
            ditherCallbacks.delete(id);
        }
    };
    console.log('Dither worker initialized');
} catch (err) {
    console.warn('Web Worker not available, using main-thread dithering');
}

// ─── Settings (set by bridge before conversion) ───────────────────────────────
let _androidSettings = {
    fontSize: 22,
    fontWeight: 400,
    lineHeight: 120,
    margin: 20,
    fontFace: 'Literata',
    textAlign: -1,
    qualityMode: 'hq',
    bitDepth: 2,
    enableDithering: true,
    ditherStrength: 70,
    wordSpacing: 100,
    hyphenation: 2,
    hyphenationLang: 'auto',
    ignoreDocMargins: true,
    fontHinting: 2,
    fontAntialiasing: 2,
    enableNegative: false,
    enableProgressBar: true,
    rotation: 0,
    progressPosition: 'bottom',
    showProgressLine: true,
    showChapterMarks: true,
    showChapterProgress: false,
    progressFullWidth: false,
    showPageInfo: true,
    showBookPercent: true,
    showChapterPage: true,
    showChapterPercent: false,
    progressFontSize: 14,
    progressEdgeMargin: 0,
    progressSideMargin: 0,
};

function getSettings() {
    return {
        ..._androidSettings,
        bitDepth: _androidSettings.qualityMode === 'hq' ? 2 : 1,
    };
}

// ─── Progress bar height constants ───────────────────────────────────────────
const PROGRESS_BAR_HEIGHT = 14;
const PROGRESS_BAR_HEIGHT_FULLWIDTH = 20;
const PROGRESS_BAR_HEIGHT_EXTENDED = 28;

// ─── Language → hyphenation pattern map ──────────────────────────────────────
const LANG_TO_PATTERN = {
    'hy': 'Armenian.pattern',
    'eu': 'Basque.pattern',
    'bg': 'Bulgarian.pattern',
    'ca': 'Catalan.pattern',
    'cs': 'Czech.pattern',
    'da': 'Danish.pattern',
    'nl': 'Dutch.pattern',
    'en-gb': 'English_GB.pattern',
    'en': 'English_US.pattern',
    'eo': 'Esperanto.pattern',
    'et': 'Estonian.pattern',
    'fi': 'Finnish.pattern',
    'fr': 'French.pattern',
    'fur': 'Friulian.pattern',
    'gl': 'Galician.pattern',
    'ka': 'Georgian.pattern',
    'de': 'German.pattern',
    'el': 'Greek.pattern',
    'hr': 'Croatian.pattern',
    'hu': 'Hungarian.pattern',
    'is': 'Icelandic.pattern',
    'ga': 'Irish.pattern',
    'it': 'Italian.pattern',
    'la': 'Latin.pattern',
    'lv': 'Latvian.pattern',
    'lt': 'Lithuanian.pattern',
    'mk': 'Macedonian.pattern',
    'no': 'Norwegian.pattern',
    'oc': 'Occitan.pattern',
    'pms': 'Piedmontese.pattern',
    'pl': 'Polish.pattern',
    'pt-br': 'Portuguese_BR.pattern',
    'pt': 'Portuguese.pattern',
    'ro': 'Romanian.pattern',
    'rm': 'Romansh.pattern',
    'ru': 'Russian.pattern',
    'sr': 'Serbian.pattern',
    'sk': 'Slovak.pattern',
    'sl': 'Slovenian.pattern',
    'es': 'Spanish.pattern',
    'sv': 'Swedish.pattern',
    'tr': 'Turkish.pattern',
    'uk': 'Ukrainian.pattern',
    'cy': 'Welsh.pattern',
    'zu': 'Zulu.pattern',
};

// ─── Font data ───────────────────────────────────────────────────────────────
const FONT_FAMILIES = {
            'Literata': {
                variants: [
                    { file: 'Literata-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/literata/Literata%5Bopsz%2Cwght%5D.ttf' },
                    { file: 'Literata-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/literata/Literata-Italic%5Bopsz%2Cwght%5D.ttf' }
                ],
                // Variable font - one file contains all weights
                isVariable: true
            },
            'Lora': {
                variants: [
                    { file: 'Lora-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/lora/Lora%5Bwght%5D.ttf' },
                    { file: 'Lora-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/lora/Lora-Italic%5Bwght%5D.ttf' }
                ],
                isVariable: true
            },
            'Merriweather': {
                variants: [
                    { file: 'Merriweather-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/merriweather/Merriweather-Regular.ttf' },
                    { file: 'Merriweather-Bold.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/merriweather/Merriweather-Bold.ttf' },
                    { file: 'Merriweather-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/merriweather/Merriweather-Italic.ttf' },
                    { file: 'Merriweather-BoldItalic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/merriweather/Merriweather-BoldItalic.ttf' }
                ]
            },
            'Open Sans': {
                variants: [
                    { file: 'OpenSans-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/opensans/OpenSans%5Bwdth%2Cwght%5D.ttf' },
                    { file: 'OpenSans-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/opensans/OpenSans-Italic%5Bwdth%2Cwght%5D.ttf' }
                ],
                isVariable: true
            },
            'Source Serif 4': {
                variants: [
                    { file: 'SourceSerif4-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/sourceserif4/SourceSerif4%5Bopsz%2Cwght%5D.ttf' },
                    { file: 'SourceSerif4-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/sourceserif4/SourceSerif4-Italic%5Bopsz%2Cwght%5D.ttf' }
                ],
                isVariable: true
            },
            'Noto Sans': {
                variants: [
                    { file: 'NotoSans-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf' },
                    { file: 'NotoSans-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/notosans/NotoSans-Italic%5Bwdth%2Cwght%5D.ttf' }
                ],
                isVariable: true
            },
            'Noto Serif': {
                variants: [
                    { file: 'NotoSerif-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/notoserif/NotoSerif%5Bwdth%2Cwght%5D.ttf' },
                    { file: 'NotoSerif-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/notoserif/NotoSerif-Italic%5Bwdth%2Cwght%5D.ttf' }
                ],
                isVariable: true
            },
            'Roboto': {
                variants: [
                    { file: 'Roboto-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf' },
                    { file: 'Roboto-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/roboto/Roboto-Italic%5Bwdth%2Cwght%5D.ttf' }
                ],
                isVariable: true
            },
            'EB Garamond': {
                variants: [
                    { file: 'EBGaramond-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/ebgaramond/EBGaramond%5Bwght%5D.ttf' },
                    { file: 'EBGaramond-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/ebgaramond/EBGaramond-Italic%5Bwght%5D.ttf' }
                ],
                isVariable: true
            },
            'Crimson Pro': {
                variants: [
                    { file: 'CrimsonPro-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/crimsonpro/CrimsonPro%5Bwght%5D.ttf' },
                    { file: 'CrimsonPro-Italic.ttf', url: 'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/crimsonpro/CrimsonPro-Italic%5Bwght%5D.ttf' }
                ],
                isVariable: true
            }
        };

        // Arabic fonts (static TTF files for each weight - avoids slow synthetic emboldening)
        // From googlefonts/noto-fonts repository (archived but has static files)
        const ARABIC_FONTS = [
            { file: 'NotoNaskhArabic-Regular.ttf', url: 'https://cdn.jsdelivr.net/gh/googlefonts/noto-fonts@main/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-Regular.ttf' },
            { file: 'NotoNaskhArabic-Medium.ttf', url: 'https://cdn.jsdelivr.net/gh/googlefonts/noto-fonts@main/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-Medium.ttf' },
            { file: 'NotoNaskhArabic-SemiBold.ttf', url: 'https://cdn.jsdelivr.net/gh/googlefonts/noto-fonts@main/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-SemiBold.ttf' },
            { file: 'NotoNaskhArabic-Bold.ttf', url: 'https://cdn.jsdelivr.net/gh/googlefonts/noto-fonts@main/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-Bold.ttf' },
        ];

const loadedFontFamilies = new Set();
const loadedPatterns = new Set();

async function fetchFontData(url) {
            try {
                const response = await fetch(url);
                if (!response.ok) {
                    console.warn(`Font not found: ${url}`);
                    return null;
                }
                return new Uint8Array(await response.arrayBuffer());
            } catch (e) {
                console.warn(`Failed to fetch font from ${url}:`, e);
                return null;
            }
        }

async function loadFontFromUrl(url, filename) {
            try {
                const response = await fetch(url);
                if (!response.ok) {
                    console.warn(`Font not found: ${url}`);
                    return false;
                }
                const data = new Uint8Array(await response.arrayBuffer());
                const ptr = Module.allocateMemory(data.length);
                Module.HEAPU8.set(data, ptr);
                const result = renderer.registerFontFromMemory(ptr, data.length, filename);
                Module.freeMemory(ptr);
                if (result) {
                    console.log(`Loaded font: ${filename} from CDN`);
                    return true;
                }
                return false;
            } catch (e) {
                console.warn(`Failed to load font ${filename}:`, e);
                return false;
            }
        }

async function loadFontFamily(familyName) {
            if (loadedFontFamilies.has(familyName)) {
                console.log(`Font family already loaded: ${familyName}`);
                return true;
            }

            const family = FONT_FAMILIES[familyName];
            if (!family) {
                console.warn(`Unknown font family: ${familyName}`);
                return false;
            }

            console.log(`Loading font family: ${familyName}...`);
            const promises = family.variants.map(v => loadFontFromUrl(v.url, v.file));
            const results = await Promise.all(promises);
            const loaded = results.filter(r => r).length;

            if (loaded > 0) {
                loadedFontFamilies.add(familyName);
                console.log(`Loaded ${loaded}/${family.variants.length} variants of ${familyName}`);
                return true;
            }
            return false;
        }

async function loadRequiredFonts() {
            console.log('Loading required fonts from CDN...');

            // Load Literata as default
            await loadFontFamily('Literata');

            // Load Arabic fallback fonts - all weight variants
            // Static TTF files avoid slow synthetic emboldening with HarfBuzz
            // Weights: Regular=400, Medium=500, SemiBold=600, Bold=700
            for (const font of ARABIC_FONTS) {
                await loadFontFromUrl(font.url, font.file);
            }
            console.log('Loaded Arabic fonts (Regular, Medium, SemiBold, Bold)');

            // Set fallback fonts for Arabic support
            if (renderer.setFallbackFontFaces) {
                renderer.setFallbackFontFaces('Literata;Noto Naskh Arabic');
            }

            return loadedFontFamilies.size > 0;
        }

function getPatternForLang(langTag) {
            if (!langTag) return 'English_US.pattern';
            const lang = langTag.toLowerCase().trim();
            // Try exact match first
            if (LANG_TO_PATTERN[lang]) return LANG_TO_PATTERN[lang];
            // Try prefix (e.g., 'en-us' -> 'en')
            const prefix = lang.split('-')[0];
            if (LANG_TO_PATTERN[prefix]) return LANG_TO_PATTERN[prefix];
            // Default fallback
            return 'English_US.pattern';
        }

async function loadHyphenationPattern(langTag) {
            const patternFile = getPatternForLang(langTag);

            // Skip if already loaded
            if (loadedPatterns.has(patternFile)) {
                console.log(`Hyphenation pattern already loaded: ${patternFile}`);
                return true;
            }

            try {
                console.log(`Loading hyphenation pattern: ${patternFile} for language: ${langTag}`);
                // Use XHR instead of fetch — fetch() can't load file:// URLs in Android WebView
                const data = await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.open('GET', `patterns/${patternFile}`, true);
                    xhr.responseType = 'arraybuffer';
                    xhr.onload = () => {
                        if (xhr.status === 200 || xhr.status === 0) {
                            resolve(new Uint8Array(xhr.response));
                        } else {
                            reject(new Error(`HTTP ${xhr.status}`));
                        }
                    };
                    xhr.onerror = () => reject(new Error('XHR error'));
                    xhr.send();
                });
                if (!data || data.length === 0) {
                    console.warn(`Pattern not found: ${patternFile}`);
                    return false;
                }
                const ptr = Module.allocateMemory(data.length);
                Module.HEAPU8.set(data, ptr);
                const result = renderer.loadHyphenationPattern(ptr, data.length, patternFile);
                Module.freeMemory(ptr);

                if (result) {
                    loadedPatterns.add(patternFile);
                    // Re-initialize to pick up new pattern
                    renderer.initHyphenation('/hyph');
                    // Activate this dictionary
                    renderer.activateHyphenationDict(patternFile);
                    console.log(`Loaded and activated hyphenation: ${patternFile}`);
                    return true;
                }
                return false;
            } catch (e) {
                console.warn(`Failed to load hyphenation pattern ${patternFile}:`, e);
                return false;
            }
        }

function getChapterInfoForPage(pageNum) {
            if (!currentToc || currentToc.length === 0) return null;

            // Find the top-level chapter that contains this page
            // Only use top-level items for the "Ch X/Y" display
            // We want the FIRST chapter whose page is <= pageNum, but we need the one
            // that is closest to pageNum without going over (like binary search)
            let topLevelChapter = null;
            let topLevelIndex = 0;
            let topLevelPage = -1;
            const topLevelTotal = currentToc.length;

            for (let i = 0; i < currentToc.length; i++) {
                const item = currentToc[i];
                // Only update if this chapter starts at a higher page than current best
                // (but still <= pageNum), OR if we don't have a match yet
                if (item.page <= pageNum && item.page > topLevelPage) {
                    topLevelChapter = item;
                    topLevelIndex = i + 1;  // 1-based index
                    topLevelPage = item.page;
                }
            }

            if (!topLevelChapter) return null;

            // Now find the most specific (deepest) chapter for progress calculation
            let currentChapter = {
                title: topLevelChapter.title,
                startPage: topLevelChapter.page,
                index: topLevelIndex,
                totalCount: topLevelTotal,
                level: 0
            };

            // Find deepest matching chapter for accurate progress within chapter
            // Only update if we find a chapter with a strictly higher page number
            let deepestPage = currentChapter.startPage;

            function findDeepestChapter(items, depth = 0) {
                for (const item of items) {
                    if (item.page <= pageNum && item.page > deepestPage) {
                        // Found a deeper chapter that starts after our current one
                        deepestPage = item.page;
                        currentChapter.startPage = item.page;
                        currentChapter.title = item.title;
                        currentChapter.level = depth;
                    }
                    if (item.children && item.children.length > 0) {
                        findDeepestChapter(item.children, depth + 1);
                    }
                }
            }

            findDeepestChapter(currentToc);

            // Find end page: first chapter/section that starts after our current start page
            let foundNext = false;
            function findNextChapter(items) {
                for (const item of items) {
                    if (foundNext) return;
                    if (item.page > currentChapter.startPage) {
                        currentChapter.endPage = item.page - 1;
                        foundNext = true;
                        return;
                    }
                    if (item.children) findNextChapter(item.children);
                }
            }
            findNextChapter(currentToc);
            if (!foundNext) {
                currentChapter.endPage = renderer.getPageCount() - 1;
            }

            return currentChapter;
        }

function findDeepestChapter(items, depth = 0) {
                for (const item of items) {
                    if (item.page <= pageNum && item.page > deepestPage) {
                        // Found a deeper chapter that starts after our current one
                        deepestPage = item.page;
                        currentChapter.startPage = item.page;
                        currentChapter.title = item.title;
                        currentChapter.level = depth;
                    }
                    if (item.children && item.children.length > 0) {
                        findDeepestChapter(item.children, depth + 1);
                    }
                }
            }

function findNextChapter(items) {
                for (const item of items) {
                    if (foundNext) return;
                    if (item.page > currentChapter.startPage) {
                        currentChapter.endPage = item.page - 1;
                        foundNext = true;
                        return;
                    }
                    if (item.children) findNextChapter(item.children);
                }
            }

function getChapterPositions() {
            const positions = [];
            const totalPages = renderer ? renderer.getPageCount() : 1;

            function extractPositions(items) {
                for (const item of items) {
                    positions.push(item.page / totalPages);
                    if (item.children && item.children.length > 0) {
                        extractPositions(item.children);
                    }
                }
            }

            if (currentToc && currentToc.length > 0) {
                extractPositions(currentToc);
            }

            return positions;
        }

function extractPositions(items) {
                for (const item of items) {
                    positions.push(item.page / totalPages);
                    if (item.children && item.children.length > 0) {
                        extractPositions(item.children);
                    }
                }
            }

function drawProgressIndicator(ctx, settings, currentPage, totalPages) {
            if (!settings.enableProgressBar) return;

            const lineThickness = 1;      // Thin base line (1px for 1-bit clarity)
            const progressThickness = 4;  // Thicker progress line for visibility
            const chapterMarkHeight = 11; // Taller chapter marks (~3px more than before)
            const edgeMargin = settings.progressEdgeMargin || 0;
            const sideMargin = settings.progressSideMargin || 0;
            const padding = 8 + sideMargin; // Base padding + side margin
            const isTop = settings.progressPosition === 'top';
            const isFullWidth = settings.progressFullWidth;
            const hasProgressLine = settings.showProgressLine || settings.showChapterProgress;
            const hasBothLines = settings.showProgressLine && settings.showChapterProgress;

            // Calculate bar height based on mode
            let barHeight = PROGRESS_BAR_HEIGHT;
            if (settings.showChapterMarks || (isFullWidth && hasBothLines)) {
                barHeight = PROGRESS_BAR_HEIGHT_EXTENDED;
            } else if (isFullWidth && hasProgressLine) {
                barHeight = PROGRESS_BAR_HEIGHT_FULLWIDTH;
            }

            // Edge margin: for top position, add margin from top edge; for bottom, add margin from bottom edge
            const baseY = isTop ? edgeMargin : SCREEN_HEIGHT - barHeight - edgeMargin;
            const centerY = baseY + barHeight / 2;

            // Colors - pure black/white only (no grays for clean 1-bit rendering)
            const isNegative = settings.enableNegative;
            const bgColor = isNegative ? '#000000' : '#ffffff';
            const textColor = isNegative ? '#ffffff' : '#000000';
            const baseLineColor = isNegative ? '#ffffff' : '#000000';
            const progressColor = isNegative ? '#ffffff' : '#000000';
            const chapterMarkColor = isNegative ? '#ffffff' : '#000000';

            // Clear background area
            ctx.fillStyle = bgColor;
            ctx.fillRect(0, baseY, SCREEN_WIDTH, barHeight);

            // Prepare text parts
            const fontSize = settings.progressFontSize || 10;
            ctx.font = `${fontSize}px sans-serif`;
            ctx.textBaseline = 'middle';

            // Left text (chapter info)
            let leftText = '';
            if (settings.showChapterPage || settings.showChapterPercent) {
                const chapterInfo = getChapterInfoForPage(currentPage);
                if (chapterInfo) {
                    const chapterPages = chapterInfo.endPage - chapterInfo.startPage + 1;
                    const pageInChapter = currentPage - chapterInfo.startPage + 1;
                    const leftParts = [];

                    if (settings.showChapterPage) {
                        leftParts.push(`${pageInChapter}/${chapterPages}`);
                    }
                    if (settings.showChapterPercent) {
                        const chapterPercent = Math.round((pageInChapter / chapterPages) * 100);
                        leftParts.push(`${chapterPercent}%`);
                    }
                    leftText = leftParts.join('  ');
                }
            }

            // Right text (book progress)
            let rightText = '';
            const rightParts = [];
            if (settings.showPageInfo) {
                rightParts.push(`${currentPage + 1}/${totalPages}`);
            }
            if (settings.showBookPercent) {
                const bookPercent = Math.round(((currentPage + 1) / totalPages) * 100);
                rightParts.push(`${bookPercent}%`);
            }
            rightText = rightParts.join('  ');

            // Calculate text widths
            const leftTextWidth = leftText ? ctx.measureText(leftText).width : 0;
            const rightTextWidth = rightText ? ctx.measureText(rightText).width : 0;

            // Calculate bar coordinates based on mode
            let barStartX, barEndX, barWidth, lineY;

            if (isFullWidth && hasProgressLine) {
                // Full width mode: line(s) on top, text below (left/right like normal)
                lineY = baseY + 4;  // First line near top of bar area
                const textY = baseY + barHeight - fontSize / 2 - 1;  // Text near bottom, accounting for font size
                barStartX = padding;
                barEndX = SCREEN_WIDTH - padding;
                barWidth = barEndX - barStartX;

                // Draw left text (chapter info)
                if (leftText) {
                    ctx.fillStyle = textColor;
                    ctx.textAlign = 'left';
                    ctx.fillText(leftText, padding, textY);
                }

                // Draw right text (book progress)
                if (rightText) {
                    ctx.fillStyle = textColor;
                    ctx.textAlign = 'right';
                    ctx.fillText(rightText, SCREEN_WIDTH - padding, textY);
                }
            } else {
                // Normal mode: text on sides, line between
                lineY = centerY;
                barStartX = padding + (leftText ? leftTextWidth + 12 : 0);
                barEndX = SCREEN_WIDTH - padding - (rightText ? rightTextWidth + 12 : 0);
                barWidth = barEndX - barStartX;

                // Draw left text
                if (leftText) {
                    ctx.fillStyle = textColor;
                    ctx.textAlign = 'left';
                    ctx.fillText(leftText, padding, centerY);
                }

                // Draw right text
                if (rightText) {
                    ctx.fillStyle = textColor;
                    ctx.textAlign = 'right';
                    ctx.fillText(rightText, SCREEN_WIDTH - padding, centerY);
                }
            }

            // Draw book progress line (entire book)
            if (settings.showProgressLine && barWidth > 0) {
                // Draw base line (thin)
                ctx.strokeStyle = baseLineColor;
                ctx.lineWidth = lineThickness;
                ctx.beginPath();
                ctx.moveTo(barStartX, lineY);
                ctx.lineTo(barEndX, lineY);
                ctx.stroke();

                // Draw progress line (thicker)
                const progress = (currentPage + 1) / totalPages;
                const progressX = barStartX + barWidth * progress;
                ctx.strokeStyle = progressColor;
                ctx.lineWidth = progressThickness;
                ctx.beginPath();
                ctx.moveTo(barStartX, lineY);
                ctx.lineTo(progressX, lineY);
                ctx.stroke();

                // Draw chapter marks as vertical ticks (only with book progress)
                if (settings.showChapterMarks) {
                    const positions = getChapterPositions();
                    ctx.strokeStyle = chapterMarkColor;
                    ctx.lineWidth = 1;
                    for (const pos of positions) {
                        const markX = barStartX + pos * barWidth;
                        if (markX >= barStartX && markX <= barEndX) {
                            ctx.beginPath();
                            ctx.moveTo(markX, lineY - chapterMarkHeight / 2);
                            ctx.lineTo(markX, lineY + chapterMarkHeight / 2);
                            ctx.stroke();
                        }
                    }
                }
            }

            // Draw chapter progress line (current chapter only)
            if (settings.showChapterProgress && barWidth > 0) {
                const chapterInfo = getChapterInfoForPage(currentPage);
                if (chapterInfo) {
                    const chapterPages = chapterInfo.endPage - chapterInfo.startPage + 1;
                    const pageInChapter = currentPage - chapterInfo.startPage + 1;
                    const chapterProgress = pageInChapter / chapterPages;

                    // Draw base line (thin) - only if book progress not shown
                    if (!settings.showProgressLine) {
                        ctx.strokeStyle = baseLineColor;
                        ctx.lineWidth = lineThickness;
                        ctx.beginPath();
                        ctx.moveTo(barStartX, lineY);
                        ctx.lineTo(barEndX, lineY);
                        ctx.stroke();
                    }

                    // Draw chapter progress - use different Y position if book progress is also shown
                    const chapterY = settings.showProgressLine ? lineY + 9 : lineY;
                    const chapterProgressX = barStartX + barWidth * chapterProgress;
                    ctx.strokeStyle = progressColor;
                    ctx.lineWidth = settings.showProgressLine ? 2 : progressThickness;
                    ctx.beginPath();
                    ctx.moveTo(barStartX, chapterY);
                    ctx.lineTo(chapterProgressX, chapterY);
                    ctx.stroke();
                }
            }
        }

function applySettings() {
            if (!renderer) return;
            const settings = getSettings();

            renderer.setFontSize(settings.fontSize);
            if (renderer.setFontWeight) {
                renderer.setFontWeight(settings.fontWeight);
            }
            renderer.setInterlineSpace(settings.lineHeight);

            // Calculate margins - add extra bottom/top margin for progress bar
            let topMargin = settings.margin;
            let bottomMargin = settings.margin;
            const edgeMargin = settings.progressEdgeMargin || 0;

            if (settings.enableProgressBar) {
                // Calculate bar height based on mode
                const hasBothLines = settings.showProgressLine && settings.showChapterProgress;
                const hasProgressLine = settings.showProgressLine || settings.showChapterProgress;
                const isFullWidth = settings.progressFullWidth;
                let progressHeight = PROGRESS_BAR_HEIGHT;
                if (settings.showChapterMarks || (isFullWidth && hasBothLines)) {
                    progressHeight = PROGRESS_BAR_HEIGHT_EXTENDED;
                } else if (isFullWidth && hasProgressLine) {
                    progressHeight = PROGRESS_BAR_HEIGHT_FULLWIDTH;
                }

                // Progress bar replaces part of the margin, not adds to it
                // Only add extra space if progressHeight > margin
                if (settings.progressPosition === 'bottom') {
                    bottomMargin = Math.max(settings.margin, progressHeight + edgeMargin);
                } else {
                    topMargin = Math.max(settings.margin, progressHeight + edgeMargin);
                }
            }

            renderer.setMargins(settings.margin, topMargin, settings.margin, bottomMargin);

            if (settings.fontFace) {
                renderer.setFontFace(settings.fontFace);
            }

            // Set text alignment for paragraphs
            if (renderer.setTextAlign) {
                renderer.setTextAlign(settings.textAlign);
            }

            // Set word spacing
            if (renderer.setWordSpacing) {
                renderer.setWordSpacing(settings.wordSpacing);
            }

            // Set hyphenation
            if (renderer.setHyphenation) {
                renderer.setHyphenation(settings.hyphenation);
            }

            // Set ignore document margins
            if (renderer.setIgnoreDocMargins) {
                renderer.setIgnoreDocMargins(settings.ignoreDocMargins);
            }

            // Set font hinting
            if (renderer.setFontHinting) {
                renderer.setFontHinting(settings.fontHinting);
            }

            // Set font antialiasing (0=none/mono, 1=big only, 2=all)
            if (renderer.setFontAntialiasing) {
                renderer.setFontAntialiasing(settings.fontAntialiasing);
            }

            // Disable CREngine's built-in status bar (we use our own)
            try {
                renderer.configureStatusBar(false, false, false, false, false, false, false, false, false);
            } catch (e) {
                // Status bar API may not be available
            }

        }

function applyDitheringAsync(imageData, bits, strength, xthMode = false) {
            return new Promise((resolve) => {
                if (!ditherWorker) {
                    // Fallback to sync dithering
                    applyDitheringSyncToData(imageData.data, SCREEN_WIDTH, SCREEN_HEIGHT, bits, strength, xthMode);
                    resolve(imageData.data);
                    return;
                }

                const id = ++ditherJobId;
                const dataCopy = new Uint8ClampedArray(imageData.data);

                ditherCallbacks.set(id, resolve);
                ditherWorker.postMessage({
                    imageData: dataCopy.buffer,
                    width: SCREEN_WIDTH,
                    height: SCREEN_HEIGHT,
                    bits,
                    strength,
                    id,
                    xthMode
                }, [dataCopy.buffer]);
            });
        }

function applyDitheringSyncToData(data, width, height, bits, strength, xthMode = false) {
            const factor = strength / 100;
            const pixelCount = width * height;

            const err7_16 = factor * 7 / 16;
            const err3_16 = factor * 3 / 16;
            const err5_16 = factor * 5 / 16;
            const err1_16 = factor * 1 / 16;

            // Quantize function based on mode
            let quantize;
            if (xthMode) {
                // XTH: 4 levels with specific thresholds matching Xteink LUT
                quantize = (val) => {
                    if (val > 212) return 255;      // White
                    else if (val > 127) return 170; // Light Gray
                    else if (val > 42) return 85;   // Dark Gray
                    else return 0;                  // Black
                };
            } else {
                // Standard linear quantization
                const levels = Math.pow(2, bits);
                const step = 255 / (levels - 1);
                const invStep = 1 / step;
                quantize = (val) => Math.round(val * invStep) * step;
            }

            const gray = new Float32Array(pixelCount);

            for (let i = 0, idx = 0; i < pixelCount; i++, idx += 4) {
                gray[i] = 0.299 * data[idx] + 0.587 * data[idx + 1] + 0.114 * data[idx + 2];
            }

            const widthM1 = width - 1;
            const heightM1 = height - 1;

            for (let y = 0; y < height; y++) {
                const rowStart = y * width;
                const nextRowStart = rowStart + width;
                const isNotLastRow = y < heightM1;

                for (let x = 0; x < width; x++) {
                    const idx = rowStart + x;
                    const oldPixel = gray[idx];
                    const newPixel = quantize(oldPixel);

                    gray[idx] = newPixel;
                    const error = oldPixel - newPixel;

                    if (x < widthM1) gray[idx + 1] += error * err7_16;
                    if (isNotLastRow) {
                        if (x > 0) gray[nextRowStart + x - 1] += error * err3_16;
                        gray[nextRowStart + x] += error * err5_16;
                        if (x < widthM1) gray[nextRowStart + x + 1] += error * err1_16;
                    }
                }
            }

            for (let i = 0, idx = 0; i < pixelCount; i++, idx += 4) {
                const g = gray[i] < 0 ? 0 : (gray[i] > 255 ? 255 : (gray[i] + 0.5) | 0);
                data[idx] = data[idx + 1] = data[idx + 2] = g;
            }
        }

function applyDithering(ctx, bits, strength, xthMode = false) {
            const imageData = ctx.getImageData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            applyDitheringSyncToData(imageData.data, SCREEN_WIDTH, SCREEN_HEIGHT, bits, strength, xthMode);
            ctx.putImageData(imageData, 0, 0);
        }

function quantizeImage(ctx, bits, xthMode = false) {
            const imageData = ctx.getImageData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            const data = imageData.data;
            const len = data.length;

            if (xthMode) {
                // XTH: 4 levels with specific thresholds matching Xteink LUT
                for (let i = 0; i < len; i += 4) {
                    const gray = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
                    let quantized;
                    if (gray > 212) quantized = 255;      // White
                    else if (gray > 127) quantized = 170; // Light Gray
                    else if (gray > 42) quantized = 85;   // Dark Gray
                    else quantized = 0;                   // Black
                    data[i] = data[i + 1] = data[i + 2] = quantized;
                }
            } else {
                // Standard linear quantization
                const levels = Math.pow(2, bits);
                const step = 255 / (levels - 1);
                const invStep = 1 / step;

                for (let i = 0; i < len; i += 4) {
                    const gray = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
                    const quantized = ((gray * invStep + 0.5) | 0) * step;
                    data[i] = data[i + 1] = data[i + 2] = quantized;
                }
            }

            ctx.putImageData(imageData, 0, 0);
        }

function applyNegative(ctx) {
            const imageData = ctx.getImageData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            const data = imageData.data;
            for (let i = 0; i < data.length; i += 4) {
                data[i] = 255 - data[i];
                data[i + 1] = 255 - data[i + 1];
                data[i + 2] = 255 - data[i + 2];
            }
            ctx.putImageData(imageData, 0, 0);
        }

function generateXtgData(canvas, bits) {
            const width = canvas.width;
            const height = canvas.height;
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const imageData = ctx.getImageData(0, 0, width, height);
            const data = imageData.data;

            // Common header writing function
            function writeHeader(view, dataSize, bitCode) {
                view.setUint8(0, 0x58); // X
                view.setUint8(1, 0x54); // T
                view.setUint8(2, 0x47); // G
                view.setUint8(3, 0x00); // version
                view.setUint16(4, width, true);
                view.setUint16(6, height, true);
                view.setUint8(8, 0); // flags
                view.setUint8(9, bitCode);
                view.setUint32(10, dataSize, true);
            }

            if (bits === 1) {
                // 1-bit: 8 pixels per byte
                const bytesPerRow = (width + 7) >> 3;
                const dataSize = bytesPerRow * height;
                const buffer = new ArrayBuffer(22 + dataSize);
                const view = new DataView(buffer);
                const dataArray = new Uint8Array(buffer);
                writeHeader(view, dataSize, 0);

                let pixelIdx = 0;
                for (let y = 0; y < height; y++) {
                    const rowOffset = 22 + y * bytesPerRow;
                    for (let x = 0; x < width; x += 8) {
                        let byte = 0;
                        const endX = Math.min(x + 8, width);
                        for (let bx = x; bx < endX; bx++) {
                            if (data[pixelIdx] >= 128) {
                                byte |= (1 << (7 - (bx - x)));
                            }
                            pixelIdx += 4;
                        }
                        dataArray[rowOffset + (x >> 3)] = byte;
                    }
                }
                return buffer;
            } else if (bits === 2) {
                // 2-bit: 4 pixels per byte
                const bytesPerRow = (width + 3) >> 2;
                const dataSize = bytesPerRow * height;
                const buffer = new ArrayBuffer(22 + dataSize);
                const view = new DataView(buffer);
                const dataArray = new Uint8Array(buffer);
                writeHeader(view, dataSize, 1);

                let pixelIdx = 0;
                for (let y = 0; y < height; y++) {
                    const rowOffset = 22 + y * bytesPerRow;
                    for (let x = 0; x < width; x += 4) {
                        let byte = 0;
                        const endX = Math.min(x + 4, width);
                        for (let bx = x; bx < endX; bx++) {
                            const level = data[pixelIdx] >> 6; // Fast division by 64
                            byte |= (level << ((3 - (bx - x)) * 2));
                            pixelIdx += 4;
                        }
                        dataArray[rowOffset + (x >> 2)] = byte;
                    }
                }
                return buffer;
            } else {
                // 4-bit: 2 pixels per byte
                const bytesPerRow = (width + 1) >> 1;
                const dataSize = bytesPerRow * height;
                const buffer = new ArrayBuffer(22 + dataSize);
                const view = new DataView(buffer);
                const dataArray = new Uint8Array(buffer);
                writeHeader(view, dataSize, 2);

                let pixelIdx = 0;
                for (let y = 0; y < height; y++) {
                    const rowOffset = 22 + y * bytesPerRow;
                    for (let x = 0; x < width; x += 2) {
                        let byte = 0;
                        const endX = Math.min(x + 2, width);
                        for (let bx = x; bx < endX; bx++) {
                            const level = data[pixelIdx] >> 4; // Fast division by 16
                            byte |= (level << ((1 - (bx - x)) * 4));
                            pixelIdx += 4;
                        }
                        dataArray[rowOffset + (x >> 1)] = byte;
                    }
                }
                return buffer;
            }
        }

function generateXthData(canvas) {
            const width = canvas.width;
            const height = canvas.height;
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const imageData = ctx.getImageData(0, 0, width, height);
            const data = imageData.data;

            // Calculate size for one bit plane (vertical scan order)
            // Columns are scanned right-to-left
            // 8 vertical pixels per byte
            const bytesPerColumn = Math.ceil(height / 8);
            const planeSize = bytesPerColumn * width;
            const dataSize = planeSize * 2; // Two planes

            const buffer = new ArrayBuffer(22 + dataSize);
            const view = new DataView(buffer);
            const dataArray = new Uint8Array(buffer);

            // Header: XTH\0
            view.setUint8(0, 0x58); // X
            view.setUint8(1, 0x54); // T
            view.setUint8(2, 0x48); // H
            view.setUint8(3, 0x00);
            view.setUint16(4, width, true);
            view.setUint16(6, height, true);
            view.setUint8(8, 0);  // colorMode
            view.setUint8(9, 0);  // compression
            view.setUint32(10, dataSize, true);

            // Plane offsets
            const plane1Offset = 22;
            const plane2Offset = 22 + planeSize;

            // Scan columns from right to left
            for (let x = width - 1; x >= 0; x--) {
                for (let y = 0; y < height; y++) {
                    const pixelIdx = (y * width + x) * 4;
                    const gray = data[pixelIdx];

                    // Map 0-255 to 0-3 (Xteink LUT with swapped middle values)
                    // 0 (00) -> White (>212)
                    // 1 (01) -> Dark Gray (42-127)  <-- SWAPPED
                    // 2 (10) -> Light Gray (127-212) <-- SWAPPED
                    // 3 (11) -> Black (<42)

                    let val;
                    if (gray > 212) val = 0;      // White
                    else if (gray > 127) val = 2; // Light Gray
                    else if (gray > 42) val = 1;  // Dark Gray
                    else val = 3;                 // Black

                    const bit1 = (val >> 1) & 1;
                    const bit2 = val & 1;

                    // Calculate byte index in the vertical column
                    // Column index is (width - 1 - x) because we scan right-to-left
                    const colIdx = (width - 1 - x);
                    const byteInCol = Math.floor(y / 8);
                    const byteIdx = colIdx * bytesPerColumn + byteInCol;

                    const bitIdx = 7 - (y % 8); // MSB is top pixel

                    if (bit1) {
                        dataArray[plane1Offset + byteIdx] |= (1 << bitIdx);
                    }
                    if (bit2) {
                        dataArray[plane2Offset + byteIdx] |= (1 << bitIdx);
                    }
                }
            }

            return buffer;
        }

async function generateXtcData(progressCallback) {
            const headerSize = 56; // increased to include chapterOffset at 0x30
            const metadataSize = 256;
            const chapterEntrySize = 96;
            const indexEntrySize = 16;

            const pageBuffers = [];
            let totalDataSize = 0;

            const settings = getSettings();
            const bits = settings.bitDepth;
            const isHQ = settings.qualityMode === 'hq';
            const pageCount = renderer.getPageCount();

            // Get chapters from TOC
            const chapters = [];
            function extractChapters(items) {
                for (const item of items) {
                    // Clamp page to valid range
                    const page = Math.max(0, Math.min(item.page, pageCount - 1));
                    chapters.push({
                        name: item.title.substring(0, 79),
                        startPage: page,
                        endPage: -1
                    });
                    if (item.children && item.children.length > 0) {
                        extractChapters(item.children);
                    }
                }
            }
            extractChapters(currentToc);

            // Sort chapters by startPage to ensure correct endPage calculation
            chapters.sort((a, b) => a.startPage - b.startPage);

            // PIPELINED RENDERING with Web Worker
            // Stage 1: Render page N with CREngine (main thread)
            // Stage 2: Dither page N-1 in Web Worker (parallel)
            // Stage 3: Finalize page N-2 (apply negative, progress, XTG)

            const tempCanvas = document.createElement('canvas');
            tempCanvas.width = SCREEN_WIDTH;
            tempCanvas.height = SCREEN_HEIGHT;
            const tempCtx = tempCanvas.getContext('2d', { willReadFrequently: true });

            // Pipeline state
            const pendingDither = []; // {page, imageData, promise}
            const PIPELINE_DEPTH = 2; // How many pages to process in parallel

            // Helper to finalize a page (negative, progress indicator, rotation, XTG/XTH)
            function finalizePage(imageData, page, settings, bits, pageCount, isHQ) {
                tempCtx.putImageData(imageData, 0, 0);

                if (settings.enableNegative) {
                    applyNegative(tempCtx);
                }

                drawProgressIndicator(tempCtx, settings, page, pageCount);

                // Handle rotation based on orientation setting
                // 0° = no rotation, 90° = landscape CW, 180° = portrait upside down, 270° = landscape CCW
                const rotation = settings.rotation;
                let finalCanvas = tempCanvas;

                if (rotation !== 0) {
                    const rotatedCanvas = document.createElement('canvas');
                    rotatedCanvas.width = DEVICE_WIDTH;
                    rotatedCanvas.height = DEVICE_HEIGHT;
                    const rCtx = rotatedCanvas.getContext('2d');

                    // Apply rotation transform
                    if (rotation === 90) {
                        // Landscape: rotate 90° CW
                        rCtx.translate(DEVICE_WIDTH, 0);
                        rCtx.rotate(90 * Math.PI / 180);
                    } else if (rotation === 180) {
                        // Portrait upside down: rotate 180°
                        rCtx.translate(DEVICE_WIDTH, DEVICE_HEIGHT);
                        rCtx.rotate(180 * Math.PI / 180);
                    } else if (rotation === 270) {
                        // Landscape: rotate 270° CW (or 90° CCW)
                        rCtx.translate(0, DEVICE_HEIGHT);
                        rCtx.rotate(270 * Math.PI / 180);
                    }
                    rCtx.drawImage(tempCanvas, 0, 0);

                    finalCanvas = rotatedCanvas;
                }

                return isHQ ? generateXthData(finalCanvas) : generateXtgData(finalCanvas, 1);
            }

            for (let page = 0; page < pageCount; page++) {
                // Update progress
                if (progressCallback) {
                    const progress = Math.round((page / pageCount) * 100);
                    progressCallback(progress, 100, `Rendering page ${page + 1} of ${pageCount}...`);
                }

                // STAGE 1: Render current page with CREngine
                renderer.goToPage(page);
                renderer.renderCurrentPage();

                const buffer = renderer.getFrameBuffer();
                const imageData = tempCtx.createImageData(SCREEN_WIDTH, SCREEN_HEIGHT);
                imageData.data.set(buffer);

                // STAGE 2: Send to worker for dithering (or quantize sync)
                if (settings.enableDithering && ditherWorker) {
                    // Start async dithering
                    const ditherPromise = applyDitheringAsync(imageData, bits, settings.ditherStrength, isHQ);
                    pendingDither.push({ page, imageData, promise: ditherPromise });
                } else {
                    // Sync path: quantize immediately
                    tempCtx.putImageData(imageData, 0, 0);
                    if (settings.enableDithering) {
                        applyDithering(tempCtx, bits, settings.ditherStrength, isHQ);
                    } else {
                        quantizeImage(tempCtx, bits, isHQ);
                    }
                    const finalImageData = tempCtx.getImageData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
                    const pageData = finalizePage(finalImageData, page, settings, bits, pageCount, isHQ);
                    pageBuffers[page] = pageData;
                    totalDataSize += pageData.byteLength;
                }

                // STAGE 3: Collect completed dithered pages
                while (pendingDither.length >= PIPELINE_DEPTH) {
                    const oldest = pendingDither.shift();
                    const ditheredData = await oldest.promise;

                    // Reconstruct imageData with dithered result
                    const finalImageData = tempCtx.createImageData(SCREEN_WIDTH, SCREEN_HEIGHT);
                    finalImageData.data.set(ditheredData);

                    const pageData = finalizePage(finalImageData, oldest.page, settings, bits, pageCount, isHQ);
                    pageBuffers[oldest.page] = pageData;
                    totalDataSize += pageData.byteLength;
                }
            }

            // Drain remaining pipeline
            while (pendingDither.length > 0) {
                const oldest = pendingDither.shift();
                const ditheredData = await oldest.promise;

                const finalImageData = tempCtx.createImageData(SCREEN_WIDTH, SCREEN_HEIGHT);
                finalImageData.data.set(ditheredData);

                const pageData = finalizePage(finalImageData, oldest.page, settings, bits, pageCount, isHQ);
                pageBuffers[oldest.page] = pageData;
                totalDataSize += pageData.byteLength;
            }

            // Assign chapter end pages
            for (let i = 0; i < chapters.length; i++) {
                if (i < chapters.length - 1) {
                    chapters[i].endPage = chapters[i + 1].startPage - 1;
                } else {
                    chapters[i].endPage = pageCount - 1;
                }
                if (chapters[i].endPage < chapters[i].startPage) {
                    chapters[i].endPage = chapters[i].startPage;
                }
            }

            const chapterCount = chapters.length;
            const hasChapters = chapterCount > 0 ? 1 : 0;

            const metadataOffset = headerSize;
            const chaptersOffset = metadataOffset + metadataSize;
            const chaptersSize = chapterCount * chapterEntrySize;
            const indexOffset = chaptersOffset + chaptersSize;
            const indexSize = pageCount * indexEntrySize;
            const dataOffset = indexOffset + indexSize;
            const totalSize = dataOffset + totalDataSize;

            const buffer = new ArrayBuffer(totalSize);
            const view = new DataView(buffer);
            const dataArray = new Uint8Array(buffer);

            // Header - XTC for 1-bit, XTCH for 2-bit
            view.setUint8(0, 0x58); // X
            view.setUint8(1, 0x54); // T
            view.setUint8(2, 0x43); // C
            view.setUint8(3, isHQ ? 0x48 : 0x00); // H for XTCH, 0 for XTC
            view.setUint16(4, 1, true); // version (uint16_t)
            view.setUint16(6, pageCount, true); // pageCount (uint16_t)
            view.setUint8(8, 0); // readDirection: 0=L→R, 1=R→L, 2=Top→Bottom
            view.setUint8(9, 1); // hasMetadata: 1 = yes
            view.setUint8(10, 0); // hasThumbnails: 0 = no
            view.setUint8(11, hasChapters); // hasChapters
            view.setUint32(12, 1, true); // currentPage (uint32_t) - XTC pages start from 1

            view.setBigUint64(16, BigInt(metadataOffset), true);
            view.setBigUint64(24, BigInt(indexOffset), true);
            view.setBigUint64(32, BigInt(dataOffset), true);
            view.setBigUint64(40, BigInt(0), true); // reserved
            view.setBigUint64(48, BigInt(chaptersOffset), true); // chapterOffset (0x30)

            // Metadata
            const encoder = new TextEncoder();
            const title = metadata.title || 'Untitled';
            const author = metadata.authors || 'Unknown';

            const titleBytes = encoder.encode(title);
            const authorBytes = encoder.encode(author);

            for (let i = 0; i < Math.min(titleBytes.length, 127); i++) {
                dataArray[metadataOffset + i] = titleBytes[i];
            }

            for (let i = 0; i < Math.min(authorBytes.length, 63); i++) {
                dataArray[metadataOffset + 0x80 + i] = authorBytes[i];
            }

            view.setUint32(metadataOffset + 0xF0, Math.floor(Date.now() / 1000), true);
            view.setUint16(metadataOffset + 0xF4, 0, true);
            view.setUint16(metadataOffset + 0xF6, chapterCount, true);

            // Chapters
            for (let i = 0; i < chapters.length; i++) {
                const chapterOffset = chaptersOffset + i * chapterEntrySize;
                const chapter = chapters[i];

                const nameBytes = encoder.encode(chapter.name);
                for (let j = 0; j < Math.min(nameBytes.length, 79); j++) {
                    dataArray[chapterOffset + j] = nameBytes[j];
                }

                view.setUint16(chapterOffset + 0x50, chapter.startPage + 1, true); // XTC pages start from 1
                view.setUint16(chapterOffset + 0x52, chapter.endPage + 1, true); // XTC pages start from 1
            }

            // Index
            let absoluteOffset = dataOffset;
            for (let i = 0; i < pageCount; i++) {
                const indexEntryAddr = indexOffset + i * indexEntrySize;
                const pageData = new Uint8Array(pageBuffers[i]);

                view.setBigUint64(indexEntryAddr, BigInt(absoluteOffset), true);
                view.setUint32(indexEntryAddr + 8, pageData.byteLength, true);
                // Always write device dimensions (480x800) - pages are rotated to match device orientation
                view.setUint16(indexEntryAddr + 12, DEVICE_WIDTH, true);
                view.setUint16(indexEntryAddr + 14, DEVICE_HEIGHT, true);

                absoluteOffset += pageData.byteLength;
            }

            // Page data
            let writeOffset = dataOffset;
            for (let i = 0; i < pageCount; i++) {
                const pageData = new Uint8Array(pageBuffers[i]);
                dataArray.set(pageData, writeOffset);
                writeOffset += pageData.byteLength;
            }

            return buffer;
        }


// Load EPUB from a Uint8Array (bridge-friendly, no DOM/File objects)
async function loadEpubFromData(data, filename) {
    if (!wasmReady || !renderer) {
        throw new Error('WASM not ready yet');
    }

    const ptr = Module.allocateMemory(data.length);
    Module.HEAPU8.set(data, ptr);

    const result = renderer.loadEpubFromMemory(ptr, data.length);
    Module.freeMemory(ptr);

    if (!result) {
        throw new Error('Failed to load EPUB');
    }

    const info = renderer.getDocumentInfo();
    metadata = {
        title: info.title || filename || 'Untitled',
        authors: info.authors || 'Unknown',
        language: info.language || ''
    };

    const settings = getSettings();
    if (settings.hyphenation) {
        const lang = settings.hyphenationLang === 'auto'
            ? (metadata.language || 'en')
            : settings.hyphenationLang;
        await loadHyphenationPattern(lang);
    }

    applySettings();

    currentToc = [];
    try {
        const toc = renderer.getToc();
        if (toc) {
            if (typeof toc === 'string') {
                currentToc = JSON.parse(toc);
            } else if (Array.isArray(toc)) {
                currentToc = toc;
            }
            // else: unknown type, leave currentToc empty
        }
    } catch(e) {
        console.warn('Could not load TOC:', e);
    }

    return {
        title: metadata.title,
        authors: metadata.authors,
        language: metadata.language,
        pageCount: renderer.getPageCount()
    };
}
