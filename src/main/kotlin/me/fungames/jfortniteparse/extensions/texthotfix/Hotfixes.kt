package me.fungames.jfortniteparse.extensions.texthotfix

import me.fungames.fortnite.api.FortniteApi
import me.fungames.fortnite.api.model.CloudStorageResponse
import me.fungames.jfortniteparse.ue4.locres.FnLanguage
import me.fungames.jfortniteparse.ue4.locres.Locres
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
        val ini = Ini()
        ini.config.isMultiSection = true
        ini.load(data.inputStream())
        return ini
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
                val parsed = LocresTextReplacementParser.parseObject(it)
                var namespace = parsed["Namespace"] as String
                if (namespace == "")
                    namespace = " "
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
                nameSpaceM[key] = values
                result[namespace] = nameSpaceM
            } catch (e : Exception) {}
        }
        return HotfixData(result)
    }
}

class HotfixData internal constructor(val data : Map<String, Map<String, Map<FnLanguage, String>>>)

object LocresTextReplacementParser {

    class LocParserException(override val message : String?, override val cause: Throwable? = null) : Exception()

    fun parse(d : String) : Any {
        val (rest, data) = parseI(d)
        if (rest != "")
            throw LocParserException("Data not parsed properly, rest '$rest'")
        return data
    }

    private fun parseI(d : String): Pair<String,Any> {
        var data = d.trimIndent()
        if (data[0] != '(')
            throw LocParserException("Invalid loc text fix data")
        data = data.substring(1, data.lastIndex)
        while (data[0] == ' ')
            data = data.substring(1)
        return if (data.takeWhile { it != ',' && it != ')' }.contains('='))
            parseObjectI(d)
        else
            parseArrayI(d)
    }

    fun parseArray(d : String) : List<Any> {
        val (rest, data) = parseArrayI(d)
        if (rest != "")
            throw LocParserException("Data not parsed properly, rest '$rest'")
        return data
    }

    private fun parseArrayI(d : String) : Pair<String,List<Any>> {
        var data = d.trimIndent()
        val list = mutableListOf<Any>()
        if (data[0] != '(')
            throw LocParserException("Invalid loc text fix data")
        data = data.substring(1)
        loop@ while (true) {
            while (data[0] == ' ')
                data = data.substring(1)
            when (data[0]) {
                '(' -> {
                    val (rest, entry) = parseI(data)
                    list.add(entry)
                    data = rest
                }
                '"' -> {
                    data = data.substring(1)
                    val entry = data.takeWhile { it != '"' }
                    list.add(entry)
                    data = data.substring(entry.length + 1)
                }
                else -> {
                    val entry = data.takeWhile { it != ',' && it != ')' }
                    list.add(entry)
                    data = data.substring(entry.length)
                }
            }
            while (data[0] == ' ')
                data = data.substring(1)
            val next = data[0]
            data = data.substring(1)
            when(next) {
                ',' -> continue@loop
                ')' -> break@loop
                else -> throw LocParserException("Invalid loc text fix data")
            }
        }
        return data to list
    }

    fun parseObject(d : String) : Map<String, Any> {
        val (rest, data) = parseObjectI(d)
        if (rest != "")
            throw LocParserException("Data not parsed properly, rest '$rest'")
        return data
    }

    private fun parseObjectI(d : String) : Pair<String, Map<String, Any>>{
        var data = d.trimIndent()
        val map = mutableMapOf<String, Any>()
        if (data[0] != '(')
            throw LocParserException("Invalid loc text fix data")
        data = data.substring(1)
        loop@ while (true) {
            while (data[0] == ' ')
                data = data.substring(1)
            val key = data.takeWhile { it != '=' }
            data = data.substring(key.length + 1)
            while (data[0] == ' ')
                data = data.substring(1)
            when (data[0]) {
                '(' -> {
                    val (rest, entry) = parseI(data)
                    map[key] = entry
                    data = rest
                }
                '"' -> {
                    data = data.substring(1)
                    val entry = data.takeWhile { it != '"' }
                    map[key] = entry
                    data = data.substring(entry.length + 1)
                }
                else -> {
                    val entry = data.takeWhile { it != ',' && it != ')' }
                    map[key] = entry
                    data = data.substring(entry.length)
                }
            }
            while (data[0] == ' ')
                data = data.substring(1)
            val next = data[0]
            data = data.substring(1)
            when(next) {
                ',' -> continue@loop
                ')' -> break@loop
                else -> throw LocParserException("Invalid loc text fix data")
            }
        }
        return data to map
    }
}