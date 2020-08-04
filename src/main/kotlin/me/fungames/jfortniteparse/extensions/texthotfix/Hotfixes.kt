package me.fungames.jfortniteparse.extensions.texthotfix

import me.fungames.fortnite.api.FortniteApi
import me.fungames.fortnite.api.model.CloudStorageResponse
import me.fungames.jfortniteparse.ue4.locres.FnLanguage
import me.fungames.jfortniteparse.ue4.locres.Locres
import org.ini4j.Config
import org.ini4j.Ini

@Suppress("EXPERIMENTAL_API_USAGE")
fun Locres.applyHotfixes(hotfixes: HotfixData) {
    hotfixes.data.forEach { (namespace, data) -> data.forEach { (key, values) ->
        val text = values[language]
        if (text != null) {
            val nameSpaceD = texts.stringData[namespace] ?: mutableMapOf()
            nameSpaceD[key] = text
            texts.stringData[namespace] = nameSpaceD
        }
    }}
}
fun Locres.applyHotfixes() = this.applyHotfixes(Hotfixes.hotfixes)

object Hotfixes {
    private val api = FortniteApi.Builder().loginAsUser(false).buildAndLogin()

    var expire = 0L
    private lateinit var hotfixData : HotfixData
    val hotfixes: HotfixData
        get() {
            if (!::hotfixData.isInitialized || System.currentTimeMillis() >= expire)
                forceRefresh()
            return hotfixData
        }

    fun forceRefresh() {
        hotfixData = loadHotfixData()
        expire = System.currentTimeMillis() + 1800000
    }

    private fun getGameIni() : Ini {
        val file = getFileList().first { it.filename == "DefaultGame.ini" }
        val data = api.fortnitePublicService.downloadCloudstorageFile(file.uniqueFilename).execute().body()?.bytes() ?: throw IllegalStateException("Couldn't get defaultgame.ini")
        Config.getGlobal().isMultiSection = true
        val str = String(data).replace("\uFEFF", "")
        return str.reader().use { Ini(it) }
    }

    private fun getFileList() : List<CloudStorageResponse> {
        val resp = api.fortnitePublicService.getCloudstorageList().execute()
        return resp.body() ?: throw IllegalStateException("Couldn't get cloudstorage list")
    }

    @Suppress("UNCHECKED_CAST")
    fun loadHotfixData() : HotfixData {
        val result = mutableMapOf<String, MutableMap<String, MutableMap<FnLanguage, String>>>()
        val ini = getGameIni()
        val section = ini["/Script/FortniteGame.FortTextHotfixConfig"]!!.getAll("+TextReplacements")
        section.forEach {
            try {
                val parsed = IniSerializationParser.parseObject(it)
                val namespace = parsed["Namespace"] as String
                val key = parsed["Key"] as String
                val localizedStrings = parsed["LocalizedStrings"] as List<List<String>>
                val nameSpaceM = result[namespace] ?: mutableMapOf()
                val values = nameSpaceM[key] ?: mutableMapOf()
                localizedStrings.forEach {langData ->
                    val lang = langData[0]
                    val text = langData[1]
                    val fnLang = FnLanguage.valueOfLanguageCode(lang)
                    values[fnLang] = text
                }
                val en = values[FnLanguage.EN]
                FnLanguage.values().forEach {lang ->
                    if (!values.containsKey(lang))
                        en?.let { en -> values[lang] = en }
                }
                nameSpaceM[key] = values
                result[namespace] = nameSpaceM
            } catch (e : Exception) {}
        }
        return HotfixData(result)
    }
}

class HotfixData internal constructor(val data : Map<String, Map<String, Map<FnLanguage, String>>>)