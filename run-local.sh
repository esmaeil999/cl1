#!/usr/bin/env bash
# اجرای محلی برای تست (قبل از پوش به گیت‌هاب)
set -euo pipefail

export DUKASCOPY_USERNAME="${DUKASCOPY_USERNAME:?set your Dukascopy username}"
export DUKASCOPY_PASSWORD="${DUKASCOPY_PASSWORD:?set your Dukascopy password}"
export DUKASCOPY_ACCOUNT_TYPE="${DUKASCOPY_ACCOUNT_TYPE:-DEMO}"
export INSTRUMENT="${INSTRUMENT:-EURUSD}"
export DATE_FROM="${DATE_FROM:-2010-01-01 00:00:00}"
export DATE_TO="${DATE_TO:-2010-01-08 00:00:00}"
export CHUNK_HOURS="${CHUNK_HOURS:-6}"
export GZIP="${GZIP:-true}"

mvn -B -ntp clean package
java -Xmx2g -jar target/tick-exporter.jar
