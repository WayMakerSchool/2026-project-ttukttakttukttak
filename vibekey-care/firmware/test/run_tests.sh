#!/bin/sh
# 펌웨어 단위 테스트 — 기기 없이 PC에서 바로 돌립니다.
#   사용법:  cd firmware/test && ./run_tests.sh
#
# 손상 주입 실험이 200만 건씩 두 번 돌아 2~3초 걸립니다.
# 난수 수열을 코드 안에 들고 있어 어느 컴파일러로 빌드해도 같은 숫자가 나옵니다.
set -e
cd "$(dirname "$0")"

# c++ / g++ / clang++ 중 있는 것을 씁니다. CXX 환경변수가 있으면 그것을 먼저.
CXX_BIN=""
for c in "$CXX" c++ g++ clang++; do
    if [ -n "$c" ] && command -v "$c" >/dev/null 2>&1; then
        CXX_BIN="$c"
        break
    fi
done

if [ -z "$CXX_BIN" ]; then
    echo "C++ 컴파일러를 찾지 못했습니다 (c++ / g++ / clang++ 중 하나가 필요합니다)." >&2
    echo "윈도우에서 MSVC만 있다면 run_tests.bat 을 쓰거나," >&2
    echo "개발자 명령 프롬프트에서 아래를 직접 실행하세요." >&2
    echo "  cl /std:c++14 /EHsc /O2 /utf-8 test_vkp.cpp && test_vkp.exe" >&2
    exit 127
fi

OUT="${TMPDIR:-/tmp}/vkp_test"
"$CXX_BIN" -std=c++11 -Wall -Wextra -O2 -o "$OUT" test_vkp.cpp
"$OUT"
