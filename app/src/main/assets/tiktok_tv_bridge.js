// Refuerzo de viewport (aquí el <head> ya existe): borra el viewport de TikTok y fija width=1280.
// Es el "cinturón y tirantes" del arreglo de recorte que también va en onPageStarted.
(function () {
    try {
        var head = document.head || document.documentElement;
        if (head) {
            var old = document.querySelectorAll('meta[name="viewport"]');
            for (var i = 0; i < old.length; i++) { if (old[i].parentNode) old[i].parentNode.removeChild(old[i]); }
            var m = document.createElement('meta');
            m.name = 'viewport';
            m.content = 'width=1280';
            head.appendChild(m);
        }
    } catch (e) {}
})();

(function () {
    'use strict';

    if (window.__TKTV_LOADED__) return;
    window.__TKTV_LOADED__ = true;

    // --- Desactivar AV1: fuerza decodificación H.264 por HARDWARE (clave en cajas Amlogic) ---
    try {
        var origIsTypeSupported = window.MediaSource && window.MediaSource.isTypeSupported;
        if (origIsTypeSupported) {
            window.MediaSource.isTypeSupported = function (type) {
                if (type && (type.toLowerCase().indexOf('av01') !== -1 || type.toLowerCase().indexOf('av1') !== -1)) return false;
                return origIsTypeSupported.call(window.MediaSource, type);
            };
        }
        var origCanPlayType = window.HTMLMediaElement && window.HTMLMediaElement.prototype.canPlayType;
        if (origCanPlayType) {
            window.HTMLMediaElement.prototype.canPlayType = function (type) {
                if (type && (type.toLowerCase().indexOf('av01') !== -1 || type.toLowerCase().indexOf('av1') !== -1)) return '';
                return origCanPlayType.call(this, type);
            };
        }
        if (window.navigator && window.navigator.mediaCapabilities && window.navigator.mediaCapabilities.decodingInfo) {
            var origDecodingInfo = window.navigator.mediaCapabilities.decodingInfo;
            window.navigator.mediaCapabilities.decodingInfo = function (configuration) {
                if (configuration && configuration.video) {
                    var ct = (configuration.video.contentType || '').toLowerCase();
                    if (ct.indexOf('av01') !== -1 || ct.indexOf('av1') !== -1) {
                        return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
                    }
                }
                return origDecodingInfo.call(window.navigator.mediaCapabilities, configuration);
            };
        }
    } catch (e) { }

    // Inject Layout Shield for Video Constraints
    try {
        var style = document.createElement('style');
        style.id = 'tktv-layout-shield';
        style.innerHTML = `
            /* GPU: elimina sombras costosas de calcular en cada frame */
            * { box-shadow: none !important; text-shadow: none !important; }

            /* Core Fix: Force feed items to max 100vh height to fix TV Canvas stretching */
            section[data-e2e="feed-video"] {
                max-height: 100vh !important;
                display: flex !important;
                justify-content: center !important;
                align-items: center !important;
                overflow: hidden !important;
            }
            /* Restaurar mecanismo de Canvas para respetar la UI de PC sin asfixiar la caja */
            section[data-e2e="feed-video"] canvas {
                display: block !important;
                height: 100vh !important;
                max-height: 100vh !important;
                width: auto !important;
                max-width: 100vw !important;
            }
            
            /* Tope de altura maestro sin rebasar pantalla */
            section[data-e2e="feed-video"] {
                max-height: 100vh !important;
                min-height: 0 !important; /* BUGFIX FATAL: TikTok impone min-height de 623px, destrozando el max-height! */
                min-width: 0 !important; /* BUGFIX: TikTok Desktop impone min-width: 348px */
                border-radius: 0 !important; /* Fix Hardware Rendering Black Screen */
            }
            
            /* Purga de bordes para que los videos Hardware-accelerated no exploten en negro */
            div[class*="DivVideoPlayerContainer"], 
            div[class*="BasePlayerContainer"],
            div[class*="DivContainer"],
            div[class*="DivBasicPlayerWrapper"] {
                max-height: 100vh !important;
                min-height: 0 !important;
                min-width: 0 !important;
                border-radius: 0 !important;
            }
            

            /* Video proporcional dentro del bounds (Contain, JAMÁS cover).
               Selector de alta especificidad para ganarle a las reglas .clase{object-fit:cover} de TikTok. */
            html body section[data-e2e="feed-video"] video,
            html body video {
                max-height: 100vh !important;
                object-fit: contain !important;
                border-radius: 0 !important;
                transform: translateZ(0);
                backface-visibility: hidden;
            }
        `;
        document.head.appendChild(style);
    } catch (e) { }

    function sendKey(key, code, charCode) {
        try {
            var params = { key: key, code: code, keyCode: charCode, which: charCode, bubbles: true, cancelable: true, view: window };
            var event = new KeyboardEvent('keydown', params);
            (document.activeElement || document.body).dispatchEvent(event);
            document.dispatchEvent(event);
            window.dispatchEvent(event);
        } catch (e) { }
    }

    var sidebarMode = false;

    // Blindaje anti-recorte: fuerza object-fit:contain INLINE (gana a cualquier hoja de estilo de TikTok)
    function hardenVideos() {
        try {
            var vs = document.getElementsByTagName('video');
            for (var i = 0; i < vs.length; i++) {
                vs[i].style.setProperty('object-fit', 'contain', 'important');
                vs[i].style.setProperty('max-height', '100vh', 'important');
                vs[i].style.setProperty('max-width', '100vw', 'important');
            }
        } catch (e) {}
    }

    // --- Detección de la X de un modal por POSICIÓN (robusto ante clases ofuscadas) ---
    function elVisible(el) {
        if (!el) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return false;
        var s = window.getComputedStyle(el);
        return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05;
    }

    function findTopModal() {
        var list = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
        var best = null, bestZ = -1;
        for (var i = 0; i < list.length; i++) {
            var d = list[i];
            if (!elVisible(d)) continue;
            var r = d.getBoundingClientRect();
            if (r.width < window.innerWidth * 0.25 || r.height < window.innerHeight * 0.15) continue;
            var z = parseInt(window.getComputedStyle(d).zIndex) || 0;
            if (z >= bestZ) { bestZ = z; best = d; }
        }
        if (best) return best;
        // Fallback: contenedor fixed/absolute grande y centrado con z-index alto
        var all = document.querySelectorAll('div, section');
        for (var k = 0; k < all.length; k++) {
            var el = all[k];
            var st = window.getComputedStyle(el);
            if (st.position !== 'fixed' && st.position !== 'absolute') continue;
            if ((parseInt(st.zIndex) || 0) < 100) continue;
            if (!elVisible(el)) continue;
            var rr = el.getBoundingClientRect();
            if (rr.width > window.innerWidth * 0.3 && rr.width < window.innerWidth * 0.98 &&
                rr.height > window.innerHeight * 0.25 &&
                rr.top < window.innerHeight * 0.5 && rr.left < window.innerWidth * 0.5) {
                return el;
            }
        }
        return null;
    }

    function findCloseInTopRight(modal) {
        var mr = modal.getBoundingClientRect();
        var cands = modal.querySelectorAll('button, svg, [role="button"], [aria-label], span[class], div[class]');
        var best = null, bestScore = Infinity;
        for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            if (!elVisible(el)) continue;
            var r = el.getBoundingClientRect();
            if (r.width > 80 || r.height > 80) continue;        // debe ser un icono pequeño
            if (r.width > mr.width * 0.35) continue;
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            if (cx < mr.left + mr.width * 0.55) continue;       // mitad derecha
            if (cy > mr.top + mr.height * 0.28) continue;       // franja superior
            var dx = mr.right - cx, dy = cy - mr.top;           // cercanía a la esquina sup-derecha
            var score = dx * dx + dy * dy;
            if (score < bestScore) { bestScore = score; best = el; }
        }
        if (best) return best.closest('button') || best.closest('[role="button"]') || best;
        return null;
    }

    function getActiveItem() {
        try {
            var containers = document.querySelectorAll('section[data-e2e="feed-video"], div[class*="DivItemContainer"]');
            var active = null;
            var minDistance = Infinity;
            var midScreen = window.innerHeight / 2;

            for (var i = 0; i < containers.length; i++) {
                var rect = containers[i].getBoundingClientRect();
                var center = rect.top + (rect.height / 2);
                var dist = Math.abs(center - midScreen);
                if (dist < minDistance) {
                    minDistance = dist;
                    active = containers[i];
                }
            }
            return active;
        } catch (e) { return null; }
    }

    window.TikTokTV = {
        enterSidebar: function () {
            sidebarMode = true;
            try {
                var activeItem = getActiveItem();
                var root = activeItem || document;
                var buttons = root.querySelectorAll('[data-e2e="like-icon"], [data-e2e="comment-icon"], [data-e2e="share-icon"], button[aria-label*="Like"]');
                
                if (buttons.length > 0) {
                    var target = buttons[0].tagName === 'BUTTON' ? buttons[0] : (buttons[0].closest('button') || buttons[0]);
                    target.focus();
                }
            } catch (e) { }
            return 'sidebar_on';
        },
        exitSidebar: function () {
            sidebarMode = false;
            if (document.activeElement) document.activeElement.blur();
            return 'sidebar_off';
        },
        select: function () {
            try {
                // Priority: click focused element in sidebar mode
                if (sidebarMode && document.activeElement && document.activeElement !== document.body) {
                    document.activeElement.click();
                    return 'sidebar_clicked';
                }

                // Context-aware video toggle
                var activeItem = getActiveItem();
                var v = activeItem ? activeItem.querySelector('video') : null;
                
                if (!v) {
                    v = Array.from(document.querySelectorAll('video')).find(function (el) {
                        return el.offsetWidth > 0 && el.offsetHeight > 0;
                    }) || document.querySelector('video');
                }

                if (v) {
                    if (v.paused) v.play().catch(function () { });
                    else v.pause();
                    return 'v_toggled';
                }
            } catch (e) { }

            // 3. Last resort: Space key
            sendKey(' ', 'Space', 32);
            return 'ok';
        },
        scrollDown: function () {
            if (sidebarMode) {
                // Inteligencia para encontrar contenedores de scroll nativos en el panel lateral
                var scrollables = document.querySelectorAll('[class*="CommentListContainer"], [class*="DivCommentContainer"], [class*="DivSidebarContainer"], .tiktok-scrollbar');
                for (var i = 0; i < scrollables.length; i++) {
                    if (scrollables[i].scrollHeight > scrollables[i].clientHeight) {
                        scrollables[i].scrollBy({ top: 250, behavior: 'smooth' });
                        return 'sidebar_down_native';
                    }
                }
                // Fallback manual a cualquier caja deslizable lateral enfocada
                if (document.activeElement && document.activeElement.scrollHeight > document.activeElement.clientHeight) {
                    document.activeElement.scrollBy({ top: 250, behavior: 'smooth' });
                    return 'sidebar_down_active';
                }
                sendKey('ArrowDown', 'ArrowDown', 40);
                return 'sidebar_down';
            }
            var btn = document.querySelector('button[data-e2e="arrow-down"], [class*="ButtonArrow"][class*="Down"]');
            if (btn) btn.click(); else sendKey('ArrowDown', 'ArrowDown', 40);
            setTimeout(hardenVideos, 350); // el nuevo video entra tras la animación de scroll
            return 'down';
        },
        scrollUp: function () {
            if (sidebarMode) {
                var scrollables = document.querySelectorAll('[class*="CommentListContainer"], [class*="DivCommentContainer"], [class*="DivSidebarContainer"], .tiktok-scrollbar');
                for (var i = 0; i < scrollables.length; i++) {
                    if (scrollables[i].scrollHeight > scrollables[i].clientHeight) {
                        scrollables[i].scrollBy({ top: -250, behavior: 'smooth' });
                        return 'sidebar_up_native';
                    }
                }
                if (document.activeElement && document.activeElement.scrollHeight > document.activeElement.clientHeight) {
                    document.activeElement.scrollBy({ top: -250, behavior: 'smooth' });
                    return 'sidebar_up_active';
                }
                sendKey('ArrowUp', 'ArrowUp', 38);
                return 'sidebar_up';
            }
            var btn = document.querySelector('button[data-e2e="arrow-up"], [class*="ButtonArrow"][class*="Up"]');
            if (btn) btn.click(); else sendKey('ArrowUp', 'ArrowUp', 38);
            setTimeout(hardenVideos, 350); // el nuevo video entra tras la animación de scroll
            return 'up';
        },
        playIfPaused: function () {
            hardenVideos();
            var v = document.querySelector('video');
            if (v && v.paused) v.play().catch(function () { });
            return 'checked';
        },
        back: function () {
            if (sidebarMode) {
                sidebarMode = false;
                if (document.activeElement) document.activeElement.blur();
                return 'sidebar_off';
            }

            // 1) Botón de cerrar visible (lista amplia, case-insensitive)
            var closeSelectors = [
                '[data-e2e="modal-close-inner-button"]',
                '[data-e2e="close-icon"]',
                '[data-e2e="close-button"]',
                '[data-e2e="browse-close"]',
                '[data-e2e*="close" i]',
                '[aria-label="Close"]',
                '[aria-label="Cerrar"]',
                '[aria-label*="close" i]',
                '[aria-label*="cerrar" i]',
                '.tiktok-overlay-close-button',
                'button[class*="close" i]',
                'div[class*="CloseIcon" i]',
                'div[class*="DivCloseWrapper" i]',
                'svg[class*="close" i]'
            ];

            for (var i = 0; i < closeSelectors.length; i++) {
                var buttons = document.querySelectorAll(closeSelectors[i]);
                for (var j = 0; j < buttons.length; j++) {
                    var btn = buttons[j];
                    // Only click if element is actually visible on screen
                    if (btn && btn.offsetWidth > 0 && btn.offsetHeight > 0) {
                        var clickTarget = btn.tagName === 'BUTTON' ? btn : (btn.closest('button') || btn);
                        clickTarget.click();
                        return 'closed_modal';
                    }
                }
            }

            // 2) Buscar la X por POSICIÓN (arriba-derecha del modal): robusto ante clases ofuscadas
            var modal = findTopModal();
            if (modal) {
                var xBtn = findCloseInTopRight(modal);
                if (xBtn) { xBtn.click(); return 'closed_modal'; }
                sendKey('Escape', 'Escape', 27); // sin X visible: último recurso
                return 'closed_modal';
            }

            return 'nav_back';
        }
    };

})();
