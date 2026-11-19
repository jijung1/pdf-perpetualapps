package app.folio.core.editing.whiteout

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage

/**
 * Whether PDFBox-Android can decode an image's colours correctly, judged from the raw PDF
 * dictionary. PDFBox-Android has no CMYK, DeviceN, Separation or Lab support: it logs an error and
 * silently treats such images as RGB, which garbles their colours. Re-encoding one of those would
 * damage the document, so callers must leave them alone (or rasterise the page instead).
 */
object ImageColorSpaces {

    fun isSafeToDecode(image: PDImage): Boolean {
        val dict = image.cosObject as? COSDictionary ?: return false
        if (dict.getBoolean(COSName.IMAGE_MASK, false) || dict.getBoolean(COSName.getPDFName("IM"), false)) return false
        val cs = dict.getDictionaryObject(COSName.COLORSPACE) ?: dict.getDictionaryObject(COSName.getPDFName("CS"))
        return isSafe(cs, depth = 0)
    }

    private fun isSafe(cs: COSBase?, depth: Int): Boolean {
        if (depth > 3) return false
        return when (val v = if (cs is COSObject) cs.`object` else cs) {
            is COSName -> v.name in SAFE_NAMES
            is COSArray -> {
                val kind = (v.getObject(0) as? COSName)?.name ?: return false
                when (kind) {
                    "ICCBased" -> ((v.getObject(1) as? COSStream)?.getInt(COSName.N, -1) ?: -1) in setOf(1, 3)
                    "CalRGB", "CalGray" -> true
                    "Indexed", "I" -> isSafe(v.get(1), depth + 1)
                    else -> false // DeviceN, Separation, Lab, Pattern…
                }
            }
            else -> false // missing or a resource-name reference we can't resolve here
        }
    }

    private val SAFE_NAMES = setOf("DeviceRGB", "RGB", "DeviceGray", "G")
}
