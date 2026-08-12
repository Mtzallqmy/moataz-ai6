from pathlib import Path

path = Path("app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt")
text = path.read_text()
old = '''                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    })
                }
'''
new = '''                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        redactHeader("Authorization")
                        redactHeader("Proxy-Authorization")
                        redactHeader("Cookie")
                        redactHeader("Set-Cookie")
                        redactHeader("api-key")
                        redactHeader("x-api-key")
                        redactHeader("x-goog-api-key")
                        redactHeader("cf-access-client-secret")
                        level = HttpLoggingInterceptor.Level.HEADERS
                    })
                }
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"debug logger block: expected 1 match, found {count}")
path.write_text(text.replace(old, new, 1))
