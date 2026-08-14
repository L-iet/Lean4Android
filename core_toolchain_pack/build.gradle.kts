plugins { id("com.android.asset-pack") }

assetPack {
    packName.set("core_toolchain_pack")
    dynamicDelivery { deliveryType.set("install-time") }
}
