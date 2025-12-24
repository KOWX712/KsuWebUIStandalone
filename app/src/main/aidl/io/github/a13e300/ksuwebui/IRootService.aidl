package io.github.a13e300.ksuwebui;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

interface IRootService {
    ParcelableListSlice<PackageInfo> getPackages(int flags);
}
