# JForex Tick Exporter

استخراج تیک‌های تاریخی از Dukascopy (JForex) به فایل CSV، به‌صورت خودکار با GitHub Actions.

استراتژی اصلی شما (`TickExporter`) بدون تغییر در منطق، به یک پروژه‌ی Maven مستقل (standalone JForex API) تبدیل شده است تا بتواند بدون پلتفرم گرافیکی و روی سرور اجرا شود.

## ساختار پروژه

```
jforex-tick-exporter/
├── pom.xml                                  # وابستگی JForex SDK + ساخت fat-jar
├── run-local.sh                             # اجرای محلی برای تست
├── src/main/java/com/example/jforex/
│   ├── Main.java                            # اتصال به Dukascopy و اجرای استراتژی (headless)
│   ├── Config.java                          # همه تنظیمات از Environment Variables
│   └── TickExporter.java                    # استراتژی شما (با retry و gzip)
└── .github/workflows/
    ├── export-ticks.yml                     # اجرای دستی برای یک بازه زمانی
    └── export-ticks-by-year.yml             # اجرای موازی، یک job برای هر سال
```

## تغییرات مهم نسبت به کد اصلی

| قبل | بعد |
|---|---|
| مقادیر `static final` داخل کد | متغیرهای محیطی: `INSTRUMENT`، `DATE_FROM`، `DATE_TO`، `CHUNK_HOURS`، `OUT_FILE` |
| مسیر `C:/temp/...` | مسیر `out/` داخل ریپو (قابل آپلود به‌عنوان artifact) |
| نیاز به اجرای دستی در JForex Platform | `Main.java` با JForex standalone API لاگین و اجرا می‌کند |
| بدون خروج از فرآیند | خروج با کد ۰ (موفق) یا ۱ (خطا) تا Action وضعیت درست را نشان دهد |
| بدون تحمل خطا | `getTicks` تا ۵ بار با backoff تلاش می‌کند |
| CSV خام | خروجی به‌صورت `.csv.gz` (قابل خاموش کردن با `GZIP=false`) |

## راه‌اندازی

1. یک ریپازیتوری خصوصی در گیت‌هاب بسازید و محتویات این پوشه را در آن پوش کنید:

```bash
cd jforex-tick-exporter
git init
git add .
git commit -m "JForex tick exporter"
git branch -M main
git remote add origin git@github.com:USERNAME/jforex-tick-exporter.git
git push -u origin main
```

2. در گیت‌هاب به `Settings → Secrets and variables → Actions` بروید و این دو Secret را بسازید:

- `DUKASCOPY_USERNAME`
- `DUKASCOPY_PASSWORD`

(حساب Demo کافی است و برای داده‌ی تاریخی توصیه می‌شود.)

3. در تب `Actions`، ورک‌فلو **Export JForex Ticks** را انتخاب و `Run workflow` را بزنید. نماد، تاریخ شروع و پایان را وارد کنید.

4. پس از پایان اجرا، فایل خروجی در بخش **Artifacts** همان اجرا قابل دانلود است.

## نکته‌ی مهم درباره بازه ۲۰۱۰ تا ۲۰۲۰

ده سال تیک EURUSD حدود چند میلیارد تیک و چندین گیگابایت است. یک job در GitHub Actions حداکثر ۶ ساعت اجرا می‌شود و سقف هر artifact محدود است، پس اجرای کل بازه در یک job شکست می‌خورد.

به همین دلیل ورک‌فلوی دوم (`export-ticks-by-year.yml`) اضافه شده است: برای هر سال یک job جدا اجرا می‌کند و یک فایل جدا می‌سازد. مقدار ورودی `years` را مثلا این‌طور بدهید:

```json
["2010","2011","2012","2013","2014"]
```

و بعد بقیه‌ی سال‌ها را در یک اجرای دیگر. اگر یک سال هم طولانی شد، ورک‌فلوی اول را با بازه‌های ماهانه اجرا کنید.

## اجرای محلی

```bash
export DUKASCOPY_USERNAME=...
export DUKASCOPY_PASSWORD=...
export DATE_FROM="2010-01-01 00:00:00"
export DATE_TO="2010-01-08 00:00:00"
bash run-local.sh
```

## متغیرهای محیطی

| نام | پیش‌فرض | توضیح |
|---|---|---|
| `DUKASCOPY_USERNAME` | — | الزامی |
| `DUKASCOPY_PASSWORD` | — | الزامی |
| `DUKASCOPY_ACCOUNT_TYPE` | `DEMO` | `DEMO` یا `LIVE` |
| `DUKASCOPY_JNLP` | خودکار | اگر بخواهید آدرس JNLP را دستی بدهید |
| `INSTRUMENT` | `EURUSD` | نام نماد در `Instrument` |
| `DATE_FROM` / `DATE_TO` | — | `yyyy-MM-dd HH:mm:ss` به وقت GMT |
| `CHUNK_HOURS` | `6` | حجم هر درخواست تاریخی |
| `SLEEP_MS` | `500` | فاصله بین درخواست‌ها |
| `MAX_RETRIES` | `5` | تعداد تلاش مجدد `getTicks` |
| `GZIP` | `true` | فشرده‌سازی خروجی |
| `OUT_FILE` | خودکار در `out/` | مسیر فایل خروجی |
| `RUN_TIMEOUT_HOURS` | `5` | سقف زمان اجرا |

## عیب‌یابی

- **خطای دانلود وابستگی:** نسخه‌ی `jforex.version` در `pom.xml` را با آخرین نسخه‌ی موجود در مخزن عمومی Dukascopy به‌روز کنید.
- **`Failed to connect`:** نام کاربری/رمز یا `DUKASCOPY_ACCOUNT_TYPE` را بررسی کنید؛ حساب دمو بعد از مدتی منقضی می‌شود.
- **تعداد تیک صفر:** بازه‌ی انتخابی آخر هفته/تعطیلی بازار است یا آن نماد در آن تاریخ داده ندارد.
- **قوانین استفاده:** داده‌ی تاریخی Dukascopy مشمول شرایط استفاده‌ی خودشان است؛ دانلود انبوه را با فاصله‌ی معقول بین درخواست‌ها انجام دهید.
