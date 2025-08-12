#!/bin/bash

# Этот скрипт содержит полезные команды для отладки Android приложений через adb logcat.

# ---

# 1. Просмотр логов только от вашего приложения (SshVpnService и MainActivity).
# Это самый эффективный способ скрыть системный "шум".
echo "--- Logs from SshVpnService and MainActivity ---"
adb logcat -c && adb logcat SshVpnService:D MainActivity:D *:S

# -c : очищает старые логи
# SshVpnService:D : показывать логи от тега SshVpnService с уровнем Debug и выше
# MainActivity:D : показывать логи от тега MainActivity с уровнем Debug и выше
# *:S : (Silent) скрыть все остальные теги

# ---

# 2. Просмотр логов с фильтрацией по пакету (менее строгий фильтр).
# Этот способ скроет большинство системных сообщений, но может оставить некоторые.
# echo "--- Logs filtered by package name ---"
# adb logcat -c && adb logcat | grep "com.example.sshproxy"

# ---

# 3. Исключение конкретных надоедливых сообщений.
# Этот способ можно использовать, если вы хотите видеть большинство системных логов,
# но скрыть конкретные сообщения, такие как 'ActivityThread'.
# echo "--- Logs with ActivityThread filtered out ---"
# adb logcat -c && adb logcat | grep -v "ActivityThread"

# ---

# Чтобы использовать этот файл:
# 1. Дайте ему права на выполнение: chmod +x debug_commands.sh
# 2. Запустите его: ./debug_commands.sh
