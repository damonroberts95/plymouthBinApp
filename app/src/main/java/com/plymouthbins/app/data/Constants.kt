package com.plymouthbins.app.data

object Constants {
    const val BASE = "https://plymouth-self.achieveservice.com"
    const val FORM_URL = "$BASE/AchieveForms/?mode=fill&consentMessage=yes" +
            "&form_uri=sandbox-publish://AF-Process-084d6742-3572-41ba-ac1a-430750451f9d" +
            "/AF-Stage-67ba684d-0a5b-48f8-9c50-1c01cc43c396/definition.json" +
            "&process=1" +
            "&process_uri=sandbox-processes://AF-Process-084d6742-3572-41ba-ac1a-430750451f9d" +
            "&process_id=AF-Process-084d6742-3572-41ba-ac1a-430750451f9d"
    const val FILLFORM_URL = "$BASE/fillform/?iframe_id=fillform-frame-1&db_id="
    const val STAGE_ID = "AF-Stage-67ba684d-0a5b-48f8-9c50-1c01cc43c396"
    const val STAGE_NAME = "Check bin day"
    const val PROCESS_ID = "AF-Process-084d6742-3572-41ba-ac1a-430750451f9d"
    const val FORM_ID = "AF-Form-2782d5d8-9470-4a9c-b5f1-5f4e83904d7f"
    const val LOOKUP_PREMISE = "69f05bb2ad2d6"
    const val LOOKUP_SCHEDULE_PRIMARY = "698b9c49a3c13"
    const val LOOKUP_SCHEDULE_SECONDARY = "69fde187c451b"
    const val LOOKUP_ADDRESS_SEARCH = "560d5266e930f"
    const val LOOKUP_COLLECTIVE_KEY = "6936e38f6d376"
    val SCHEDULE_LOOKUPS = listOf(LOOKUP_SCHEDULE_PRIMARY, LOOKUP_SCHEDULE_SECONDARY)
    val SCHEDULE_FETCH_ALLOWLIST = setOf(
        LOOKUP_PREMISE, LOOKUP_SCHEDULE_PRIMARY, LOOKUP_SCHEDULE_SECONDARY,
    )

    // Tunables
    const val SAVED_CREDS_TTL_MS = 25L * 60_000L
    const val EMPTY_RECAPTURE_THRESHOLD = 3
    const val POST_PICK_DWELL_MS = 7_000L

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"
}
