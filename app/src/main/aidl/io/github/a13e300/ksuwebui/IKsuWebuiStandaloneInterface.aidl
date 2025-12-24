package io.github.a13e300.ksuwebui;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

interface IKsuWebuiStandaloneInterface {
    ParcelableListSlice<PackageInfo> getPackages(int flags);
}
