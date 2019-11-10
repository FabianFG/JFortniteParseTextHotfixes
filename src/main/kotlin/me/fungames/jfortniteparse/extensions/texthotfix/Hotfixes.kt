package me.fungames.jfortniteparse.extensions.texthotfix

import me.fungames.jfortniteparse.ue4.locres.FnLanguage
import me.fungames.jfortniteparse.ue4.locres.Locres
import okhttp3.ResponseBody
import org.ini4j.Ini
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates


fun Locres.applyHotfixes() {
    Hotfixes.hotfixes.data.forEach { (namespace, data) -> data.forEach { (key, values) ->
        val text = values[language]
        if (text != null) {
            val nameSpaceD = texts.stringData[namespace] ?: mutableMapOf()
            nameSpaceD[key] = text
            texts.stringData[namespace] = nameSpaceD
        }
    }}
}

object Hotfixes {
    private val retrofit = Retrofit.Builder()
        .addConverterFactory(
            GsonConverterFactory.create())
        .baseUrl("https://account-public-service-prod.ol.epicgames.com")
        .build()
    private val accountPublicService = retrofit.create(AccountPublicService::class.java)!!
    private val fortnitePublicService = retrofit.newBuilder().baseUrl("https://fortnite-public-service-prod11.ol.epicgames.com").build().create(FortnitePublicService::class.java)!!

    private lateinit var token : String
    private var tokenExpire by Delegates.notNull<Long>()

    private var expire = 0L
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

    private fun login(): Boolean {
        if (::token.isInitialized)
            return refreshToken()
        val resp = accountPublicService.grantToken("basic MzQ0NmNkNzI2OTRjNGE0NDg1ZDgxYjc3YWRiYjIxNDE6OTIwOWQ0YTVlMjVhNDU3ZmI5YjA3NDg5ZDMxM2I0MWE", "client_credentials", emptyMap(), null).execute()
        if (!resp.isSuccessful)
            return false
        this.token = resp.body()?.access_token ?: return false
        this.tokenExpire = System.currentTimeMillis() + (resp.body()?.expires_in ?: return false)
        return true
    }

    private fun refreshToken() : Boolean {
        val resp = accountPublicService.grantToken("basic MzQ0NmNkNzI2OTRjNGE0NDg1ZDgxYjc3YWRiYjIxNDE6OTIwOWQ0YTVlMjVhNDU3ZmI5YjA3NDg5ZDMxM2I0MWE", "refresh_token", mapOf("refresh_token" to token), null).execute()
        if (!resp.isSuccessful)
            return false
        this.token = resp.body()?.access_token ?: return false
        this.tokenExpire = System.currentTimeMillis() + (resp.body()?.expires_in ?: return false)
        return true
    }

    private fun verifyToken() : Boolean {
        if (!::token.isInitialized)
            return login()
        else if (System.currentTimeMillis() > TimeUnit.MILLISECONDS.convert(tokenExpire, TimeUnit.SECONDS))
            return refreshToken()
        return true
    }

    private fun getGameIni() : Ini {
        val file = getFileList().first { it.filename == "DefaultGame.ini" }
        val data = fortnitePublicService.downloadCloudstorageFile("bearer $token", file.uniqueFilename).execute().body()?.bytes() ?: throw IllegalStateException("Couldn't get defaultgame.ini")
        val ini = Ini()
        ini.config.isMultiSection = true
        ini.load(data.inputStream())
        return ini
    }

    private fun getFileList() : List<Model.CloudStorageResponse> {
        check(verifyToken()) { "Could not login into fortnite api" }
        val resp = fortnitePublicService.getCloudstorageList("bearer $token").execute()
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

private interface AccountPublicService {
    /**
     * grant_type: authorization_code; fields: code
     * grant_type: client_credentials -- used to retrieve client ID without sign in
     * grant_type: device_auth; fields: account_id, device_id, secret
     * grant_type: exchange_code; fields: exchange_code
     * grant_type: external_auth; fields: external_auth_type, external_auth_token
     * grant_type: otp; fields: otp, challenge
     * grant_type: password; fields: username, password
     * grant_type: refresh_token; fields: refresh_token
     */
    @FormUrlEncoded
    @POST("/account/api/oauth/token")
    fun grantToken(
        @Header("Authorization") auth: String, @Field("grant_type") grantType: String, @FieldMap fields: Map<String, String>, @Field(
            "includePerms"
        ) includePerms: Boolean?
    ): Call<Model.LoginResponse>
}

private interface FortnitePublicService {
    @GET("/fortnite/api/cloudstorage/system")
    fun getCloudstorageList(@Header("Authorization") auth: String) : Call<List<Model.CloudStorageResponse>>
    @GET("/fortnite/api/cloudstorage/system/{uniqueFilename}")
    fun downloadCloudstorageFile(@Header("Authorization") auth: String, @Path("uniqueFilename") uniqueFilename: String) : Call<ResponseBody>
}

private object Model {
    data class LoginResponse (

        val access_token : String,
        val expires_in : Int,
        val expires_at : String,
        val token_type : String,
        val client_id : String,
        val internal_client : Boolean,
        val client_service : String
    )

    data class CloudStorageResponse (
        val uniqueFilename : String,
        val filename : String,
        val hash : String,
        val hash256 : String,
        val length : Int,
        val contentType : String,
        val uploaded : String,
        val storageType : String,
        val doNotCache : Boolean
    )
}

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