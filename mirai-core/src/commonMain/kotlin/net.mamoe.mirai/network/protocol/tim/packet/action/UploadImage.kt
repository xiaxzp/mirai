@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_UNSIGNED_LITERALS", "unused", "NO_REFLECTION_IN_CLASS_PATH")

package net.mamoe.mirai.network.protocol.tim.packet.action

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.userAgent
import kotlinx.coroutines.withContext
import kotlinx.io.charsets.Charsets
import kotlinx.io.core.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.message.ImageId
import net.mamoe.mirai.message.requireLength
import net.mamoe.mirai.network.BotNetworkHandler
import net.mamoe.mirai.network.protocol.tim.TIMProtocol
import net.mamoe.mirai.network.protocol.tim.packet.*
import net.mamoe.mirai.network.protocol.tim.packet.event.EventPacket
import net.mamoe.mirai.network.qqAccount
import net.mamoe.mirai.qqAccount
import net.mamoe.mirai.utils.ExternalImage
import net.mamoe.mirai.utils.Http
import net.mamoe.mirai.utils.configureBody
import net.mamoe.mirai.utils.io.*
import net.mamoe.mirai.withSession
import kotlin.coroutines.coroutineContext

/**
 * 图片文件过大
 */
class OverFileSizeMaxException : IllegalStateException()

/**
 * 上传群图片
 * 挂起直到上传完成或失败
 *
 * 在 JVM 下, `SendImageUtilsJvm.kt` 内有多个捷径函数
 *
 * @throws OverFileSizeMaxException 如果文件过大, 服务器拒绝接收时
 */
suspend fun Group.uploadImage(image: ExternalImage): ImageId = withSession {
    val userContext = coroutineContext
    val response = GroupImageIdRequestPacket(bot.qqAccount, internalId, image, sessionKey).sendAndExpect<GroupImageIdRequestPacket.Response>()

    withContext(userContext) {
        when (response) {
            is GroupImageIdRequestPacket.Response.RequireUpload -> Http.postImage(
                htcmd = "0x6ff0071",
                uin = bot.qqAccount,
                groupId = GroupId(id),
                imageInput = image.input,
                inputSize = image.inputSize,
                uKeyHex = response.uKey.toUHexString("")
            )

            is GroupImageIdRequestPacket.Response.AlreadyExists -> {
            }

            is GroupImageIdRequestPacket.Response.OverFileSizeMax -> throw OverFileSizeMaxException()
        }
    }

    return image.groupImageId
}

/**
 * 上传图片
 * 挂起直到上传完成或失败
 *
 * 在 JVM 下, `SendImageUtilsJvm.kt` 内有多个捷径函数
 *
 * @throws OverFileSizeMaxException 如果文件过大, 服务器拒绝接收时
 */
suspend fun QQ.uploadImage(image: ExternalImage): ImageId = bot.withSession {
    FriendImagePacket.RequestImageId(qqAccount, sessionKey, id, image)
        .sendAndExpectAsync<ImageResponse, ImageId> {
            return@sendAndExpectAsync when (it) {
                is ImageUKey -> {
                    Http.postImage(
                        htcmd = "0x6ff0070",
                        uin = bot.qqAccount,
                        groupId = null,
                        uKeyHex = it.uKey.toUHexString(""),
                        imageInput = image.input,
                        inputSize = image.inputSize
                    )
                    it.imageId
                }
                is ImageAlreadyExists -> it.imageId
                is ImageOverFileSizeMax -> throw OverFileSizeMaxException()
                else -> error("This shouldn't happen")
            }
        }.await()
}

@Suppress("SpellCheckingInspection")
internal suspend inline fun HttpClient.postImage(
    htcmd: String,
    uin: UInt,
    groupId: GroupId?,
    imageInput: Input,
    inputSize: Long,
    uKeyHex: String
): Boolean = try {
    post<HttpStatusCode> {
        url {
            protocol = URLProtocol.HTTP
            host = "htdata2.qq.com"
            path("cgi-bin/httpconn")

            parameters["htcmd"] = htcmd
            parameters["uin"] = uin.toLong().toString()

            if (groupId != null) parameters["groupcode"] = groupId.value.toLong().toString()

            parameters["term"] = "pc"
            parameters["ver"] = "5603"
            parameters["filesize"] = inputSize.toString()
            parameters["range"] = 0.toString()
            parameters["ukey"] = uKeyHex

            userAgent("QQClient")
        }

        configureBody(inputSize, imageInput)
    } == HttpStatusCode.OK
} finally {
    imageInput.close()
}

/*
/**
 * 似乎没有必要. 服务器的返回永远都是 01 00 00 00 02 00 00
 */
@Deprecated("Useless packet")
@AnnotatedId(KnownPacketId.SUBMIT_IMAGE_FILE_NAME)
@PacketVersion(date = "2019.10.26", timVersion = "2.3.2 (21173)")
object SubmitImageFilenamePacket : PacketFactory {
    operator fun invoke(
        bot: UInt,
        target: UInt,
        filename: String,
        sessionKey: SessionKey
    ): OutgoingPacket = buildOutgoingPacket {
        writeQQ(bot)
        writeFully(TIMProtocol.fixVer2)//?
        //writeHex("04 00 00 00 01 2E 01 00 00 69 35")

        encryptAndWrite(sessionKey) {
            writeByte(0x01)
            writeQQ(bot)
            writeQQ(target)
            writeZero(2)
            writeUByte(0x02u)
            writeRandom(1)
            writeHex("00 0A 00 01 00 01")
            val name = "UserDataImage:$filename"
            writeShort(name.length.toShort())
            writeStringUtf8(name)
            writeHex("00 00")
            writeRandom(2)//这个也与是哪个好友有关?
            writeHex("00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 2E 01")//35  02? 最后这个值是与是哪个好友有关

            //this.debugPrintThis("SubmitImageFilenamePacket")
        }

        //解密body=01 3E 03 3F A2 7C BC D3 C1 00 00 27 1A 00 0A 00 01 00 01 00 30 55 73 65 72 44 61 74 61 43 75 73 74 6F 6D 46 61 63 65 3A 31 5C 28 5A 53 41 58 40 57 4B 52 4A 5A 31 7E 33 59 4F 53 53 4C 4D 32 4B 49 2E 6A 70 67 00 00 06 E2 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 2F 02
        //解密body=01 3E 03 3F A2 7C BC D3 C1 00 00 27 1B 00 0A 00 01 00 01 00 30 55 73 65 72 44 61 74 61 43 75 73 74 6F 6D 46 61 63 65 3A 31 5C 28 5A 53 41 58 40 57 4B 52 4A 5A 31 7E 33 59 4F 53 53 4C 4D 32 4B 49 2E 6A 70 67 00 00 06 E2 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 2F 02
        //解密body=01 3E 03 3F A2 7C BC D3 C1 00 00 27 1C 00 0A 00 01 00 01 00 30 55 73 65 72 44 61 74 61 43 75 73 74 6F 6D 46 61 63 65 3A 31 5C 29 37 42 53 4B 48 32 44 35 54 51 28 5A 35 7D 35 24 56 5D 32 35 49 4E 2E 6A 70 67 00 00 03 73 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 2F 02
    }

    @PacketVersion(date = "2019.10.19", timVersion = "2.3.2 (21173)")
    class Response {
        override fun decode() = with(input) {
            require(readBytes().contentEquals(expecting))
        }

        companion object {
            private val expecting = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00)
        }
    }
}*/

interface ImageResponse : EventPacket

/**
 * 图片数据地址.
 */
// TODO: 2019/11/15 应该为 inline class, 但 kotlin 有 bug
data class ImageLink(inline val value: String) : ImageResponse {
    suspend fun downloadAsByteArray(): ByteArray = download().readBytes()
    suspend fun download(): ByteReadPacket = Http.get(value)

    override fun toString(): String = "ImageLink($value)"
}

/**
 * 访问 HTTP API 时使用的 uKey
 */
class ImageUKey(inline val imageId: ImageId, inline val uKey: ByteArray) : ImageResponse {
    override fun toString(): String = "ImageUKey(imageId=${imageId.value}, uKey=${uKey.toUHexString()})"
}

/**
 * 图片 ID 已存在
 * 发送消息时使用的 id
 */
inline class ImageAlreadyExists(inline val imageId: ImageId) : ImageResponse {
    override fun toString(): String = "FriendImageAlreadyExists(imageId=${imageId.value})"
}

/**
 * 超过文件大小上限
 */
object ImageOverFileSizeMax : ImageResponse {
    override fun toString(): String = "FriendImageOverFileSizeMax"
}

/**
 * 请求上传图片. 将发送图片的 md5, size, width, height.
 * 服务器返回以下之一:
 * - 服务器已经存有这个图片
 * - 服务器未存有, 返回一个 key 用于客户端上传
 */
@AnnotatedId(KnownPacketId.FRIEND_IMAGE_ID)
@PacketVersion(date = "2019.11.16", timVersion = "2.3.2 (21173)")
object FriendImagePacket : SessionPacketFactory<ImageResponse>() {
    @Suppress("FunctionName")
    fun RequestImageId(
        bot: UInt,
        sessionKey: SessionKey,
        target: UInt,
        image: ExternalImage
    ): OutgoingPacket = buildSessionPacket(bot, sessionKey, version = TIMProtocol.version0x04) {
        writeHex("00 00 00 07 00 00")

        writeShortLVPacket(lengthOffset = { it - 7 }) {
            writeUByte(0x08u)
            writeTV(0x01_12u)
            writeTV(0x03_98u)
            writeTV(0x01_01u)
            writeTV(0x08_01u)

            writeUVarIntLVPacket(tag = 0x12u, lengthOffset = { it + 1 }) {
                writeTUVarint(0x08u, bot)
                writeTUVarint(0x10u, target)
                writeTV(0x18_00u)
                writeTLV(0x22u, image.md5)
                writeTUVarint(0x28u, image.inputSize.toUInt())
                writeUVarIntLVPacket(tag = 0x32u) {
                    writeTV(0x28_00u)
                    writeTV(0x46_00u)
                    writeTV(0x51_00u)
                    writeTV(0x56_00u)
                    writeTV(0x4B_00u)
                    writeTV(0x41_00u)
                    writeTV(0x49_00u)
                    writeTV(0x25_00u)
                    writeTV(0x4B_00u)
                    writeTV(0x24_00u)
                    writeTV(0x55_00u)
                    writeTV(0x30_00u)
                    writeTV(0x24_00u)
                }
                writeTV(0x38_01u)
                writeTV(0x48_00u)
                writeTUVarint(0x70u, image.width.toUInt())
                writeTUVarint(0x78u, image.height.toUInt())
            }
        }

    }

    @Suppress("FunctionName")
    fun RequestImageLink(
        bot: UInt,
        sessionKey: SessionKey,
        imageId: ImageId
    ): OutgoingPacket {
        imageId.requireLength()
        require(imageId.value.length == 37) { "ImageId.value.length must == 37" }

        // 00 00 00 07 00 00 00
        // [4B]
        // 08
        // 01 12
        // 03 98
        // 01 02
        // 08 02
        //
        // 1A [47]
        // 08 [A2 FF 8C F0 03] UVarInt
        // 10 [DD F1 92 B7 07] UVarInt
        // 1A [25] 2F 38 65 32 63 32 38 62 64 2D 35 38 61 31 2D 34 66 37 30 2D 38 39 61 31 2D 65 37 31 39 66 63 33 30 37 65 65 66
        // 20 02 30 04 38 20 40 FF 01 50 00 6A 05 32 36 39 33 33 78 01


        // 00 00 00 07 00 00 00
        // [4B]
        // 08
        // 01 12
        // 03 98
        // 01 02
        // 08 02
        //
        // 1A
        // [47]
        // 08 [A2 FF 8C F0 03]
        // 10 [A6 A7 F1 EA 02]
        // 1A [25] 2F 39 61 31 66 37 31 36 32 2D 38 37 30 38 2D 34 39 30 38 2D 38 31 63 30 2D 66 34 63 64 66 33 35 63 38 64 37 65
        // 20 02 30 04 38 20 40 FF 01 50 00 6A 05 32 36 39 33 33 78 01


        return buildSessionPacket(bot, sessionKey, version = TIMProtocol.version0x04) {
            writeHex("00 00 00 07 00 00")

            writeUShort(0x004Bu)

            writeUByte(0x08u)
            writeTV(0x01_12u)
            writeTV(0x03_98u)
            writeTV(0x01_02u)
            writeTV(0x08_02u)

            writeUByte(0x1Au)
            writeUByte(0x47u)
            writeTUVarint(0x08u, bot)
            writeTUVarint(0x10u, bot)
            writeTLV(0x1Au, imageId.value.toByteArray(Charsets.ISO_8859_1))
            writeHex("20 02 30 04 38 20 40 FF 01 50 00 6A 05 32 36 39 33 33 78 01")
        }
    }

    override suspend fun ByteReadPacket.decode(id: PacketId, sequenceId: UShort, handler: BotNetworkHandler<*>): ImageResponse =
        with(this) {

            // 上传图片, 成功获取ID
            //00 00 00 08 00 00
            // [01 0D]
            // 12 [06] 98 01 01 A0 01 00
            // 08 01 //packet type 01=上传图片; 02=下载图片
            // 12 [86 02]
            //   08 00
            //   10 [9B A4 D4 9A 0A]
            //   18 00
            //   28 00
            //   38 F1 C0 A1 BF 05
            //   38 BB C8 E4 E2 0F
            //   38 FB AE FA 9D 0A
            //   38 E5 C6 8B CD 06
            //   40 BB 03 // ports
            //   40 90 3F
            //   40 50
            //   40 BB 03
            //   4A [80 01] 76 B2 58 23 B8 F6 B1 E6 AE D4 76 EC 3C 08 79 B1 DF 05 D5 C2 4A E0 CC F1 2F 26 4F D4 DC 44 5A 9A 16 A9 E4 22 EB 92 96 05 C3 C9 8F C5 5F 84 00 A3 4E 63 BE 76 F7 B9 7B 09 43 A6 14 EE C8 6D 6A 48 02 E3 9D 62 CD 42 3E 15 93 64 8F FC F5 88 50 74 6A 6A 03 C9 FE F0 96 EA 76 02 DC 4F 09 D0 F5 60 73 B2 62 8F 8B 11 06 BF 06 1B 18 00 FE B4 5E F3 12 72 F2 66 9C F5 01 97 1C 0A 5B 68 5B 85 ED 9C
            //   52 [25] 2F 37 38 62 36 34 64 63 32 2D 31 66 32 31 2D 34 33 62 38 2D 39 32 62 31 2D 61 30 35 30 35 30 34 30 35 66 65 32
            //   5A [25] 2F 37 38 62 36 34 64 63 32 2D 31 66 32 31 2D 34 33 62 38 2D 39 32 62 31 2D 61 30 35 30 35 30 34 30 35 66 65 32
            //   60 00 68 80 80 08
            // 20 01

            // 上传图片, 图片过大
            //00 00 00 09 00 00 00 1D 12 07 98 01 01 A0 01 C7 01 08 01 12 19 08 00 18 C7 01 22 12 66 69 6C 65 20 73 69 7A 65 20 6F 76 65 72 20 6D 61 78
            discardExact(3) // 00 00 00
            if (readUByte().toUInt() == 0x09u) {
                return ImageOverFileSizeMax
            }
            discardExact(2) //00 00

            discardExact(2) //全长 (有 offset)

            discardExact(1); discardExact(readUVarInt().toInt()) // 12 06 98 01 01 A0 01 00

            check(readUByte().toUInt() == 0x08u)
            when (val flag = readUByte().toUInt()) {
                0x01u -> {
                    try {
                        while (readUByte().toUInt() != 0x4Au) readUVarLong()
                        val uKey = readBytes(readUVarInt().toInt())//128
                        while (readUByte().toUInt() != 0x52u) readUVarLong()
                        val imageId = ImageId(readString(readUVarInt().toInt()))//37
                        return ImageUKey(imageId, uKey)
                    } catch (e: EOFException) {
                        val toDiscard = readUByte().toInt() - 37

                        return if (toDiscard < 0) {
                            ImageOverFileSizeMax
                        } else {
                            discardExact(toDiscard)
                            val imageId = ImageId(readString(37))
                            ImageAlreadyExists(imageId)
                        }
                    }
                }
                0x02u -> {
                    //00 00 00 08 00 00
                    // [02 2B]
                    // 12 [06] 98 01 02 A0 01 00
                    // 08 02
                    // 1A [A6 04]
                    //   0A [25] 2F 38 65 32 63 32 38 62 64 2D 35 38 61 31 2D 34 66 37 30 2D 38 39 61 31 2D 65 37 31 39 66 63 33 30 37 65 65 66
                    //   18 00
                    //   32 [7B] 68 74 74 70 3A 2F 2F 36 31 2E 31 35 31 2E 32 33 34 2E 35 34 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 38 65 32 63 32 38 62 64 2D 35 38 61 31 2D 34 66 37 30 2D 38 39 61 31 2D 65 37 31 39 66 63 33 30 37 65 65 66 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33 32 7C 68 74 74 70 3A 2F 2F 31 30 31 2E 32 32 37 2E 31 33 31 2E 36 37 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 38 65 32 63 32 38 62 64 2D 35 38 61 31 2D 34 66 37 30 2D 38 39 61 31 2D 65 37 31 39 66 63 33 30 37 65 65 66 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33 32 7D 68 74 74 70 3A 2F 2F 31 35 37 2E 32 35 35 2E 31 39 32 2E 31 30 35 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 38 65 32 63 32 38 62 64 2D 35 38 61 31 2D 34 66 37 30 2D 38 39 61 31 2D 65 37 31 39 66 63 33 30 37 65 65 66 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33 32 7C 68 74 74 70 3A 2F 2F 31 32 30 2E 32 34 31 2E 31 39 30 2E 34 31 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 38 65 32 63 32 38 62 64 2D 35 38 61 31 2D 34 66 37 30 2D 38 39 61 31 2D 65 37 31 39 66 63 33 30 37 65 65 66 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33
                    //   3A 00 80 01 00


                    //00 00 00 08 00 00
                    // [02 29]
                    // 12 [06] 98 01 02 A0 01 00
                    // 08 02
                    // 1A [A4 04]
                    //   0A [25] 2F 62 61 65 30 63 64 66 66 2D 65 33 34 30 2D 34 38 39 34 2D 39 37 36 65 2D 30 66 62 35 38 61 61 31 36 35 66 64
                    //   18 00
                    //   32 [7A] 68 74 74 70 3A 2F 2F 31 30 31 2E 38 39 2E 33 39 2E 32 31 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 62 61 65 30 63 64 66 66 2D 65 33 34 30 2D 34 38 39 34 2D 39 37 36 65 2D 30 66 62 35 38 61 61 31 36 35 66 64 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33
                    //   32 7B 68 74 74 70 3A 2F 2F 36 31 2E 31 35 31 2E 31 38 33 2E 32 31 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 62 61 65 30 63 64 66 66 2D 65 33 34 30 2D 34 38 39 34 2D 39 37 36 65 2D 30 66 62 35 38 61 61 31 36 35 66 64 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33 32 7D 68 74 74 70 3A 2F 2F 31 35 37 2E 32 35 35 2E 31 39 32 2E 31 30 35 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 62 61 65 30 63 64 66 66 2D 65 33 34 30 2D 34 38 39 34 2D 39 37 36 65 2D 30 66 62 35 38 61 61 31 36 35 66 64 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33 32 7C 68 74 74 70 3A 2F 2F 31 32 30 2E 32 34 31 2E 31 39 30 2E 34 31 3A 38 30 2F 6F 66 66 70 69 63 5F 6E 65 77 2F 31 30 34 30 34 30 30 32 39 30 2F 2F 62 61 65 30 63 64 66 66 2D 65 33 34 30 2D 34 38 39 34 2D 39 37 36 65 2D 30 66 62 35 38 61 61 31 36 35 66 64 2F 30 3F 76 75 69 6E 3D 31 30 34 30 34 30 30 32 39 30 26 74 65 72 6D 3D 32 35 35 26 73 72 76 76 65 72 3D 32 36 39 33 33 3A 00 80 01 00

                    discardExact(1)
                    discardExact(2)// [A4 04] 后文长度
                    check(readUByte().toUInt() == 0x0Au) { "Illegal identity. Required 0x0Au" }
                    /* val imageId = */ImageId(readString(readUByte().toInt()))

                    check(readUByte().toUInt() == 0x18u) { "Illegal identity. Required 0x18u" }
                    check(readUShort().toUInt() == 0x0032u) { "Illegal identity. Required 0x0032u" }

                    val link = readUVarIntLVString()
                    discard()
                    ImageLink(link)
                }
                else -> error("Unknown FriendImageIdRequestPacket flag $flag")
            }
        }
}


/**
 * 获取 Image Id 和上传用的一个 uKey
 */
@AnnotatedId(KnownPacketId.GROUP_IMAGE_ID)
@PacketVersion(date = "2019.10.26", timVersion = "2.3.2 (21173)")
object GroupImageIdRequestPacket : SessionPacketFactory<GroupImageIdRequestPacket.Response>() {
    operator fun invoke(
        bot: UInt,
        groupInternalId: GroupInternalId,
        image: ExternalImage,
        sessionKey: SessionKey
    ): OutgoingPacket = buildOutgoingPacket {
        writeQQ(bot)
        writeHex("04 00 00 00 01 01 01 00 00 68 20 00 00 00 00 00 00 00 00")

        encryptAndWrite(sessionKey) {
            writeHex("00 00 00 07 00 00")

            writeShortLVPacket(lengthOffset = { it - 7 }) {
                writeByte(0x08)
                writeHex("01 12 03 98 01 01 10 01 1A")

                writeUVarIntLVPacket(lengthOffset = { it }) {
                    writeTUVarint(0x08u, groupInternalId.value)
                    writeTUVarint(0x10u, bot)
                    writeTV(0x1800u)

                    writeUByte(0x22u)
                    writeUByte(0x10u)
                    writeFully(image.md5)

                    writeTUVarint(0x28u, image.inputSize.toUInt())
                    writeUVarIntLVPacket(tag = 0x32u) {
                        writeTV(0x5B_00u)
                        writeTV(0x40_00u)
                        writeTV(0x33_00u)
                        writeTV(0x48_00u)
                        writeTV(0x5F_00u)
                        writeTV(0x58_00u)
                        writeTV(0x46_00u)
                        writeTV(0x51_00u)
                        writeTV(0x45_00u)
                        writeTV(0x51_00u)
                        writeTV(0x40_00u)
                        writeTV(0x24_00u)
                        writeTV(0x4F_00u)
                    }
                    writeTV(0x38_01u)
                    writeTV(0x48_01u)
                    writeTUVarint(0x50u, image.width.toUInt())
                    writeTUVarint(0x58u, image.height.toUInt())
                    writeTV(0x60_04u)//这个似乎会变 有时候是02, 有时候是03
                    writeTByteArray(0x6Au, value0x6A)

                    writeTV(0x70_00u)
                    writeTV(0x78_03u)
                    writeTV(0x80_01u)
                    writeUByte(0u)
                }
            }
        }
    }

    private val value0x6A: UByteArray = ubyteArrayOf(0x05u, 0x32u, 0x36u, 0x36u, 0x35u, 0x36u)

    sealed class Response : EventPacket {
        data class RequireUpload(
            /**
             * 访问 HTTP API 时需要使用的一个 key. 128 位
             */
            val uKey: ByteArray
        ) : Response() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is RequireUpload) return false
                if (!uKey.contentEquals(other.uKey)) return false
                return true
            }

            override fun hashCode(): Int = uKey.contentHashCode()
        }

        object AlreadyExists : Response() {
            override fun toString(): String = this::class.simpleName!!
        }

        /**
         * 超过文件大小上限
         */
        object OverFileSizeMax : Response() {
            override fun toString(): String = this::class.simpleName!!
        }
    }

    override suspend fun ByteReadPacket.decode(id: PacketId, sequenceId: UShort, handler: BotNetworkHandler<*>): Response {
        discardExact(6)//00 00 00 05 00 00

        val length = remaining - 128 - 14
        if (length < 0) {
            return if (readUShort().toUInt() == 0x0025u) Response.OverFileSizeMax else Response.AlreadyExists
        }

        discardExact(length)
        return Response.RequireUpload(readBytes(128))
    }
}