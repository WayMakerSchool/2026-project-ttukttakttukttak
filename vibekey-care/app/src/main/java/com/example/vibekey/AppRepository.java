package com.example.vibekey;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 설치된 앱 목록을 읽고, 이름/키워드로 찾아 주는 저장소입니다.
 * AI(제미나이)를 못 쓰는 상황에서도 앱을 찾을 수 있도록
 * 한국어 키워드 사전({@link KeywordMatcher})을 이용한 오프라인 검색을 함께 제공합니다.
 */
public class AppRepository {

    private final Context context;
    private List<AppItem> cache;

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 홈 화면에 아이콘이 있는(=실행 가능한) 앱을 이름순으로 돌려줍니다. */
    public synchronized List<AppItem> getInstalledApps() {
        if (cache != null) {
            return cache;
        }
        final PackageManager pm = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);

        List<AppItem> items = new ArrayList<>();
        for (ResolveInfo info : resolveInfos) {
            String packageName = info.activityInfo.packageName;
            if (context.getPackageName().equals(packageName)) {
                continue; // 자기 자신은 목록에서 제외
            }
            String label = info.loadLabel(pm).toString();
            Drawable icon = info.loadIcon(pm);
            items.add(new AppItem(label, packageName, icon));
        }

        Collections.sort(items, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        cache = items;
        return cache;
    }

    public synchronized void invalidate() {
        cache = null;
    }

    public AppItem findByPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        for (AppItem item : getInstalledApps()) {
            if (item.packageName.equals(packageName)) {
                return item;
            }
        }
        return null;
    }

    /** 사용자가 입력한 글자로 앱 이름을 걸러 냅니다. */
    public List<AppItem> filterByText(String query) {
        List<AppItem> all = getInstalledApps();
        if (TextUtils.isEmpty(query)) {
            return all;
        }
        String needle = query.toLowerCase(Locale.KOREA).trim();
        List<AppItem> result = new ArrayList<>();
        for (AppItem item : all) {
            if (item.label.toLowerCase(Locale.KOREA).contains(needle)
                    || item.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * AI를 쓸 수 없을 때 사용하는 오프라인 추측 검색입니다.
     * "길 찾고 싶어" 처럼 말해도 키워드 사전으로 후보를 찾아 줍니다.
     */
    public AppItem guessByNaturalLanguage(String sentence) {
        if (TextUtils.isEmpty(sentence)) {
            return null;
        }
        String text = sentence.toLowerCase(Locale.KOREA);

        // 1) 앱 이름이 문장에 그대로 들어 있으면 그 앱을 먼저 선택
        AppItem bestByName = null;
        for (AppItem item : getInstalledApps()) {
            String label = item.label.toLowerCase(Locale.KOREA);
            if (label.length() >= 2 && text.contains(label)) {
                if (bestByName == null || label.length() > bestByName.label.length()) {
                    bestByName = item;
                }
            }
        }
        if (bestByName != null) {
            return bestByName;
        }

        // 2) 키워드 사전으로 후보 패키지를 찾고, 설치된 것 중 첫 번째를 선택
        for (String candidate : KeywordMatcher.candidatesFor(text)) {
            AppItem found = findByPackage(candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 제미나이에게 넘길 "패키지명 | 앱이름" 목록을 만듭니다. */
    public String buildAppCatalogForPrompt(int limit) {
        StringBuilder sb = new StringBuilder();
        List<AppItem> apps = getInstalledApps();
        int count = 0;
        for (AppItem item : apps) {
            if (count >= limit) {
                break;
            }
            sb.append(item.packageName).append(" | ").append(item.label).append('\n');
            count++;
        }
        return sb.toString();
    }
}
