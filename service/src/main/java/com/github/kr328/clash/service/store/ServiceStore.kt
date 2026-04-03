package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "system"
    )

    var tunMtu by store.int(
        key = "tun_mtu",
        defaultValue = 9000
    )

    var tunStrictRoute by store.boolean(
        key = "tun_strict_route",
        defaultValue = false
    )

    var tunDnsHijackList by store.string(
        key = "tun_dns_hijack_list",
        defaultValue = "any:53"
    )

    var tunAutoRoute by store.boolean(
        key = "tun_auto_route",
        defaultValue = true
    )

    var tunAutoDetectInterface by store.boolean(
        key = "tun_auto_detect_interface",
        defaultValue = true
    )

    var tunDnsEnable by store.boolean(
        key = "tun_dns_enable",
        defaultValue = true
    )

    var tunDnsMode by store.string(
        key = "tun_dns_mode",
        defaultValue = "fake-ip"
    )

    var tunDnsNameservers by store.string(
        key = "tun_dns_nameservers",
        defaultValue = "114.114.114.114,8.8.8.8"
    )

    var tunDnsFakeIpRange by store.string(
        key = "tun_dns_fake_ip_range",
        defaultValue = "198.18.0.1/16"
    )

    var tunDnsFallback by store.string(
        key = "tun_dns_fallback",
        defaultValue = "1.1.1.1,8.8.4.4"
    )

    var tunExcludeInterfaces by store.string(
        key = "tun_exclude_interfaces",
        defaultValue = "ap0,wlan1,softap0,rndis0"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )

    var persistOverrideJson by store.string(
        key = "persist_override_json",
        defaultValue = "{}"
    )

    var sessionOverrideJson by store.string(
        key = "session_override_json",
        defaultValue = "{}"
    )
}
