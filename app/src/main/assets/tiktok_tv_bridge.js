(function () {
    'use strict';

    if (window.__TKTV_LOADED__) return;
    window.__TKTV_LOADED__ = true;

    // Inject Layout Shield for Video Constraints
    try {
        var style = document.createElement('style');
        style.id = 'tktv-layout-shield';
        style.innerHTML = `
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
            

            /* Video Estricto proporcional dentro del bounds (Contain, JAMÁS cover) */
            video {
                max-height: 100vh !important;
                object-fit: contain !important; 
                border-radius: 0 !important;
                transform: translateZ(0); 
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
            return 'up';
        },
        playIfPaused: function () {
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

            var closeSelectors = [
                '[data-e2e="modal-close-inner-button"]',
                '[data-e2e="close-icon"]',
                '[data-e2e="close-button"]',
                '[aria-label="Close"]',
                '[aria-label="Cerrar"]',
                '.tiktok-overlay-close-button',
                'button[class*="close" i]',
                'div[class*="CloseIcon"]',
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

            return 'nav_back';
        }
    };

    // --- PERF & DOM DUMPER FOR ADB ANALYSIS ---
    setTimeout(function () {
        try {
            if (window.AndroidHost && window.AndroidHost.saveDOM) {
                var report = "=== TIKTOK TV SYSTEM DUMP ===\n";
                report += "Window: " + window.innerWidth + "x" + window.innerHeight + "\n";

                // 1. PERFORMANCE METRICS
                report += "\n--- PERFORMANCE METRICS ---\n";
                if (window.performance && window.performance.timing) {
                    var t = window.performance.timing;
                    report += "Page Load Time: " + (t.loadEventEnd - t.navigationStart) + "ms\n";
                    report += "DOM Ready: " + (t.domComplete - t.domInteractive) + "ms\n";
                    report += "TTFB: " + (t.responseStart - t.requestStart) + "ms\n";
                }
                if (window.performance && window.performance.memory) {
                    var mem = window.performance.memory;
                    report += "JS Heap: " + Math.round(mem.usedJSHeapSize / 1048576) + " MB / " + Math.round(mem.totalJSHeapSize / 1048576) + " MB\n";
                }

                // 2. SIDEBAR DEBUGGING
                report += "\n--- SIDEBAR (ASIDE) CSS ---\n";
                var sidebar = document.querySelector('aside');
                if (sidebar) {
                    var cb = window.getComputedStyle(sidebar);
                    report += "Width: " + cb.width + " | Height: " + cb.height + "\n";
                    report += "Position: " + cb.position + " | Left: " + cb.left + " | Right: " + cb.right + "\n";
                    report += "Transform: " + cb.transform + "\n";
                    report += "MarginLeft: " + cb.marginLeft + " | PaddingLeft: " + cb.paddingLeft + "\n";
                } else {
                    report += "NO ASIDE FOUND\n";
                }

                var main = document.querySelector('main');
                if (main) {
                    var cm = window.getComputedStyle(main);
                    report += "\n--- MAIN CSS ---\n";
                    report += "Width: " + cm.width + " | Display: " + cm.display + " | FlexDirection: " + cm.flexDirection + "\n";
                    report += "Padding: " + cm.padding + " | Margin: " + cm.margin + "\n";
                }

                window.AndroidHost.saveDOM(report);
            }
        } catch (e) { }
    }, 7000);
})();
