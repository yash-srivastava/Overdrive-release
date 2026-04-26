/**
 * BYD Champ - Client-side Auth Helper
 * 
 * Handles JWT session management and auth state.
 * Uses both cookie AND localStorage for redundancy (tunnels can drop cookies).
 * Include this in pages that need auth checking.
 */

const BYDAuth = {
    // Storage keys
    COOKIE_NAME: 'byd_session',
    STORAGE_KEY: 'byd_jwt',
    
    // Check if user is authenticated
    isAuthenticated: function() {
        return !!this.getToken();
    },
    
    // Get JWT from cookie OR localStorage (fallback)
    getToken: function() {
        // Try cookie first
        const cookies = document.cookie.split(';');
        for (let cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === this.COOKIE_NAME && value) {
                return value;
            }
        }
        
        // Fallback to localStorage (tunnels sometimes drop cookies)
        try {
            const stored = localStorage.getItem(this.STORAGE_KEY);
            if (stored) {
                console.log('JWT loaded from localStorage (cookie was missing)');
                // Try to restore the cookie
                this._setCookie(stored);
                return stored;
            }
        } catch (e) {
            // localStorage might be blocked
        }
        
        return null;
    },
    
    // Set JWT in both cookie AND localStorage
    setToken: function(jwt, maxAgeDays = 365) {
        this._setCookie(jwt, maxAgeDays);
        
        // Also store in localStorage as backup
        try {
            localStorage.setItem(this.STORAGE_KEY, jwt);
        } catch (e) {
            console.warn('Could not save JWT to localStorage:', e);
        }
    },
    
    // Internal: set cookie only
    _setCookie: function(jwt, maxAgeDays = 365) {
        const maxAge = maxAgeDays * 24 * 60 * 60;
        const isSecure = window.location.protocol === 'https:';
        // Use Lax for HTTPS through tunnels, works better than Strict
        const sameSite = isSecure ? 'Lax; Secure' : 'Lax';
        document.cookie = `${this.COOKIE_NAME}=${jwt}; path=/; max-age=${maxAge}; SameSite=${sameSite}`;
    },
    
    // Clear JWT from both cookie AND localStorage
    clearToken: function() {
        document.cookie = `${this.COOKIE_NAME}=; path=/; max-age=0`;
        try {
            localStorage.removeItem(this.STORAGE_KEY);
        } catch (e) {
            // Ignore
        }
    },
    
    // Get auth headers for fetch requests
    // Always include Authorization header (more reliable than cookies through tunnels)
    getAuthHeaders: function() {
        const token = this.getToken();
        if (token) {
            return { 'Authorization': 'Bearer ' + token };
        }
        return {};
    },
    
    // Authenticated fetch wrapper
    // ALWAYS sends Authorization header (doesn't rely on cookies)
    fetch: async function(url, options = {}) {
        const headers = {
            ...options.headers,
            ...this.getAuthHeaders()
        };
        
        const response = await fetch(url, { ...options, headers });
        
        // Handle 401 - redirect to login
        if (response.status === 401) {
            const currentPath = window.location.pathname + window.location.search;
            window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
            throw new Error('Unauthorized');
        }
        
        return response;
    },
    
    // Check auth status from server
    checkStatus: async function() {
        try {
            const response = await fetch('/auth/status', {
                headers: this.getAuthHeaders()
            });
            return await response.json();
        } catch (e) {
            console.error('Auth status check failed:', e);
            return { deviceId: null };
        }
    },
    
    // Logout
    logout: async function() {
        try {
            await fetch('/auth/logout', { 
                method: 'POST',
                headers: this.getAuthHeaders()
            });
        } catch (e) {
            // Ignore errors
        }
        this.clearToken();
        window.location.href = '/login.html';
    },
    
    // Redirect to login if not authenticated
    requireAuth: function() {
        if (!this.isAuthenticated()) {
            const currentPath = window.location.pathname + window.location.search;
            window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
            return false;
        }
        return true;
    }
};

// SOTA: Override global fetch to automatically inject Authorization header.
// This ensures all API calls work through tunnels (where cookies get dropped due to SameSite policy).
// Only injects for same-origin or relative URL requests (not external APIs like ABRP/weather).
(function() {
    const originalFetch = window.fetch;
    window.fetch = function(url, options) {
        // Only inject auth for relative URLs or same-origin requests
        if (typeof url === 'string' && (url.startsWith('/') || url.startsWith(window.location.origin))) {
            const token = BYDAuth.getToken();
            if (token) {
                options = options || {};
                options.headers = options.headers || {};
                // Don't override if Authorization is already set
                if (!options.headers['Authorization'] && !options.headers['authorization']) {
                    if (options.headers instanceof Headers) {
                        if (!options.headers.has('Authorization')) {
                            options.headers.set('Authorization', 'Bearer ' + token);
                        }
                    } else {
                        options.headers['Authorization'] = 'Bearer ' + token;
                    }
                }
            }
        }
        return originalFetch.call(this, url, options);
    };
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BYDAuth;
}
