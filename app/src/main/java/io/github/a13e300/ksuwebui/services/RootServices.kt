package io.github.a13e300.ksuwebui.services

import android.content.pm.PackageInfo
import android.os.UserManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import io.github.a13e300.ksuwebui.IKsuWebuiStandaloneInterface
import rikka.parcelablelist.ParcelableListSlice

class RootServices : RootService() {
    private val TAG = "RootServices"

    override fun onBind(intent: android.content.Intent): android.os.IBinder {
        return Stub()
    }

    private fun getUserIds(): List<Int> {
        val result = mutableListOf<Int>()
        val um = getSystemService(USER_SERVICE) as UserManager
        val userProfiles = um.userProfiles
        for (userProfile in userProfiles) {
            result.add(userProfile.hashCode())
        }
        return result
    }

    private fun getInstalledPackagesAll(flags: Int): ArrayList<PackageInfo> {
        val packages = ArrayList<PackageInfo>()
        for (userId in getUserIds()) {
            Log.i(TAG, "getInstalledPackagesAll: $userId")
            packages.addAll(getInstalledPackagesAsUser(flags, userId))
        }
        return packages
    }

    private fun getInstalledPackagesAsUser(flags: Int, userId: Int): List<PackageInfo> {
        return try {
            val pm = packageManager
            val method = pm.javaClass.getDeclaredMethod("getInstalledPackagesAsUser", Int::class.java, Int::class.java)
            method.invoke(pm, flags, userId) as List<PackageInfo>
        } catch (e: Throwable) {
            Log.e(TAG, "err", e)
            emptyList()
        }
    }

    private inner class Stub : IKsuWebuiStandaloneInterface.Stub() {
        override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
            val list = getInstalledPackagesAll(flags)
            Log.i(TAG, "getPackages: ${list.size}")
            return ParcelableListSlice(list)
        }
    }
}
