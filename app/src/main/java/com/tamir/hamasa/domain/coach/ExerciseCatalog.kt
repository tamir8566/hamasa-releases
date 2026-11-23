package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity

/**
 * Full Hebrew exercise catalog.
 * Equipment: "barbell" | "dumbbell" | "machine" | "cable" | "body" | "band" | "kettlebell" | "trx".
 * Unit: "kg" (weighted), "reps" (bodyweight, count reps), "sec" (hold / time based).
 * alternativesCsv: 2–4 ids from the same branch.
 */
object ExerciseCatalog {

    private fun ex(
        id: String, nameHe: String, branch: Branch, equipment: String, compound: Boolean,
        unit: String = "kg", alt: String = ""
    ) = ExerciseEntity(id, nameHe, branch, equipment, compound, unit, alt)

    // ---------------------------------------------------------------- CHEST
    private val chest = listOf(
        ex("bench_press", "לחיצת חזה במוט", Branch.CHEST, "barbell", true, alt = "db_bench_press,chest_press_machine,incline_bench_press"),
        ex("incline_bench_press", "לחיצת חזה בשיפוע עם מוט", Branch.CHEST, "barbell", true, alt = "incline_db_press,bench_press,chest_press_machine"),
        ex("db_bench_press", "לחיצת חזה עם משקולות", Branch.CHEST, "dumbbell", true, alt = "bench_press,chest_press_machine,pushup"),
        ex("incline_db_press", "לחיצת חזה בשיפוע עם משקולות", Branch.CHEST, "dumbbell", true, alt = "incline_bench_press,db_bench_press,chest_press_machine"),
        ex("chest_press_machine", "לחיצת חזה במכונה", Branch.CHEST, "machine", true, alt = "bench_press,db_bench_press,pushup"),
        ex("chest_fly", "פרפר במכונה", Branch.CHEST, "machine", false, alt = "cable_crossover,db_fly,chest_press_machine"),
        ex("db_fly", "פרפר עם משקולות", Branch.CHEST, "dumbbell", false, alt = "chest_fly,cable_crossover"),
        ex("cable_crossover", "הצלבת כבלים", Branch.CHEST, "cable", false, alt = "chest_fly,db_fly"),
        ex("pushup", "שכיבות סמיכה", Branch.CHEST, "body", true, unit = "reps", alt = "db_bench_press,chest_press_machine,decline_pushup,dips"),
        ex("decline_pushup", "שכיבות סמיכה עם רגליים מורמות", Branch.CHEST, "body", true, unit = "reps", alt = "pushup,dips,db_bench_press"),
        ex("dips", "מקבילים", Branch.CHEST, "body", true, unit = "reps", alt = "pushup,decline_pushup,chest_press_machine")
    )

    // ----------------------------------------------------------------- BACK
    private val back = listOf(
        ex("lat_pulldown", "משיכת פולי עליון", Branch.BACK, "cable", true, alt = "pullup,assisted_pullup,straight_arm_pulldown"),
        ex("cable_row", "חתירה בכבל", Branch.BACK, "cable", true, alt = "machine_row,db_row,barbell_row"),
        ex("machine_row", "חתירה במכונה", Branch.BACK, "machine", true, alt = "cable_row,db_row,t_bar_row"),
        ex("barbell_row", "חתירה עם מוט", Branch.BACK, "barbell", true, alt = "db_row,cable_row,t_bar_row"),
        ex("db_row", "חתירה עם משקולת ביד אחת", Branch.BACK, "dumbbell", true, alt = "cable_row,barbell_row,machine_row"),
        ex("t_bar_row", "חתירת טי-בר", Branch.BACK, "machine", true, alt = "barbell_row,machine_row,cable_row"),
        ex("deadlift", "דדליפט", Branch.BACK, "barbell", true, alt = "barbell_row,back_extension,t_bar_row"),
        ex("pullup", "מתח", Branch.BACK, "body", true, unit = "reps", alt = "lat_pulldown,assisted_pullup,inverted_row"),
        ex("assisted_pullup", "מתח במכונת סיוע", Branch.BACK, "machine", true, alt = "lat_pulldown,pullup,inverted_row"),
        ex("inverted_row", "חתירה אופקית במוט", Branch.BACK, "body", true, unit = "reps", alt = "pullup,cable_row,machine_row"),
        ex("straight_arm_pulldown", "משיכת כבל בידיים ישרות", Branch.BACK, "cable", false, alt = "lat_pulldown,back_extension"),
        ex("back_extension", "פשיטת גב", Branch.BACK, "body", false, unit = "reps", alt = "deadlift,straight_arm_pulldown")
    )

    // ----------------------------------------------------------------- LEGS
    private val legs = listOf(
        ex("leg_press", "לחיצת רגליים", Branch.LEGS, "machine", true, alt = "squat,hack_squat,goblet_squat,bodyweight_squat"),
        ex("squat", "סקוואט עם מוט", Branch.LEGS, "barbell", true, alt = "leg_press,hack_squat,goblet_squat"),
        ex("hack_squat", "האק סקוואט", Branch.LEGS, "machine", true, alt = "leg_press,squat,goblet_squat"),
        ex("goblet_squat", "סקוואט גביע", Branch.LEGS, "dumbbell", true, alt = "leg_press,bodyweight_squat,squat"),
        ex("bodyweight_squat", "סקוואט במשקל גוף", Branch.LEGS, "body", true, unit = "reps", alt = "goblet_squat,leg_press,lunges"),
        ex("romanian_deadlift", "דדליפט רומני", Branch.LEGS, "barbell", true, alt = "leg_curl,hip_thrust,db_romanian_deadlift"),
        ex("db_romanian_deadlift", "דדליפט רומני עם משקולות", Branch.LEGS, "dumbbell", true, alt = "romanian_deadlift,leg_curl,glute_bridge"),
        ex("hip_thrust", "דחיקת אגן עם מוט", Branch.LEGS, "barbell", true, alt = "glute_bridge,romanian_deadlift,leg_press"),
        ex("glute_bridge", "גשר ישבן", Branch.LEGS, "body", true, unit = "reps", alt = "hip_thrust,bodyweight_squat,lunges"),
        ex("lunges", "מכרעים עם משקולות", Branch.LEGS, "dumbbell", true, alt = "bulgarian_split_squat,goblet_squat,bodyweight_squat"),
        ex("bulgarian_split_squat", "סקוואט בולגרי", Branch.LEGS, "dumbbell", true, alt = "lunges,goblet_squat,leg_press"),
        ex("leg_extension", "פשיטת ברכיים במכונה", Branch.LEGS, "machine", false, alt = "leg_press,hack_squat,bodyweight_squat"),
        ex("leg_curl", "כפיפת ברכיים במכונה", Branch.LEGS, "machine", false, alt = "romanian_deadlift,db_romanian_deadlift,glute_bridge"),
        ex("calf_raise", "הרמת עקבים במכונה", Branch.LEGS, "machine", false, alt = "bodyweight_calf_raise,leg_press"),
        ex("bodyweight_calf_raise", "הרמת עקבים במשקל גוף", Branch.LEGS, "body", false, unit = "reps", alt = "calf_raise,glute_bridge"),
        ex("hip_abduction", "פיסוק ירכיים במכונה", Branch.LEGS, "machine", false, alt = "glute_bridge,hip_thrust,lunges")
    )

    // -------------------------------------------------------- SHOULDERS/ARMS
    private val shouldersArms = listOf(
        ex("shoulder_press", "לחיצת כתפיים עם משקולות", Branch.SHOULDERS_ARMS, "dumbbell", true, alt = "machine_shoulder_press,barbell_ohp,pike_pushup"),
        ex("barbell_ohp", "לחיצת כתפיים עם מוט", Branch.SHOULDERS_ARMS, "barbell", true, alt = "shoulder_press,machine_shoulder_press"),
        ex("machine_shoulder_press", "לחיצת כתפיים במכונה", Branch.SHOULDERS_ARMS, "machine", true, alt = "shoulder_press,barbell_ohp,pike_pushup"),
        ex("pike_pushup", "שכיבות סמיכה בפייק", Branch.SHOULDERS_ARMS, "body", true, unit = "reps", alt = "shoulder_press,machine_shoulder_press"),
        ex("lateral_raise", "הרחקת כתפיים עם משקולות", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "cable_lateral_raise,lateral_raise_machine,front_raise"),
        ex("cable_lateral_raise", "הרחקת כתפיים בכבל", Branch.SHOULDERS_ARMS, "cable", false, alt = "lateral_raise,lateral_raise_machine"),
        ex("lateral_raise_machine", "הרחקת כתפיים במכונה", Branch.SHOULDERS_ARMS, "machine", false, alt = "lateral_raise,cable_lateral_raise"),
        ex("front_raise", "הרמת ידיים קדימה", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "lateral_raise,shoulder_press"),
        ex("face_pull", "משיכה לפנים בכבל", Branch.SHOULDERS_ARMS, "cable", false, alt = "reverse_fly,reverse_fly_machine,band_pull_apart"),
        ex("reverse_fly", "פרפר הפוך עם משקולות", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "face_pull,reverse_fly_machine,band_pull_apart"),
        ex("reverse_fly_machine", "פרפר הפוך במכונה", Branch.SHOULDERS_ARMS, "machine", false, alt = "face_pull,reverse_fly"),
        ex("band_pull_apart", "פתיחת גומייה", Branch.SHOULDERS_ARMS, "band", false, unit = "reps", alt = "face_pull,reverse_fly"),
        ex("db_shrug", "משיכת כתפיים עם משקולות", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "face_pull,lateral_raise"),
        ex("db_curl", "כפיפת מרפקים עם משקולות", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "hammer_curl,barbell_curl,cable_curl,preacher_curl"),
        ex("hammer_curl", "כפיפת מרפקים פטיש", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "db_curl,cable_curl,barbell_curl"),
        ex("barbell_curl", "כפיפת מרפקים עם מוט", Branch.SHOULDERS_ARMS, "barbell", false, alt = "db_curl,cable_curl,preacher_curl"),
        ex("cable_curl", "כפיפת מרפקים בכבל", Branch.SHOULDERS_ARMS, "cable", false, alt = "db_curl,hammer_curl,preacher_curl"),
        ex("preacher_curl", "כפיפת מרפקים במכונה", Branch.SHOULDERS_ARMS, "machine", false, alt = "db_curl,cable_curl,barbell_curl"),
        ex("tricep_pushdown", "פשיטת מרפקים בכבל", Branch.SHOULDERS_ARMS, "cable", false, alt = "overhead_tricep_extension,skull_crusher,tricep_machine,bench_dips"),
        ex("tricep_machine", "פשיטת מרפקים במכונה", Branch.SHOULDERS_ARMS, "machine", false, alt = "tricep_pushdown,overhead_tricep_extension"),
        ex("overhead_tricep_extension", "פשיטת מרפקים מעל הראש", Branch.SHOULDERS_ARMS, "dumbbell", false, alt = "tricep_pushdown,skull_crusher,bench_dips"),
        ex("skull_crusher", "פשיטת מרפקים בשכיבה עם מוט", Branch.SHOULDERS_ARMS, "barbell", false, alt = "tricep_pushdown,overhead_tricep_extension"),
        ex("bench_dips", "מקבילים על ספסל", Branch.SHOULDERS_ARMS, "body", false, unit = "reps", alt = "tricep_pushdown,overhead_tricep_extension,tricep_machine")
    )

    // ----------------------------------------------------------------- CORE
    private val core = listOf(
        ex("plank", "פלאנק", Branch.CORE, "body", false, unit = "sec", alt = "side_plank,dead_bug,bird_dog"),
        ex("side_plank", "פלאנק צדי", Branch.CORE, "body", false, unit = "sec", alt = "plank,dead_bug,bird_dog"),
        ex("hanging_leg_raise", "הרמת רגליים בתלייה", Branch.CORE, "body", false, unit = "reps", alt = "lying_leg_raise,cable_crunch,ab_wheel"),
        ex("lying_leg_raise", "הרמת רגליים בשכיבה", Branch.CORE, "body", false, unit = "reps", alt = "hanging_leg_raise,dead_bug,bicycle_crunch"),
        ex("cable_crunch", "כפיפת בטן בכבל", Branch.CORE, "cable", false, alt = "crunch_machine,hanging_leg_raise,ab_wheel"),
        ex("crunch_machine", "כפיפת בטן במכונה", Branch.CORE, "machine", false, alt = "cable_crunch,bicycle_crunch"),
        ex("dead_bug", "חיפושית מתה", Branch.CORE, "body", false, unit = "reps", alt = "plank,bird_dog,lying_leg_raise"),
        ex("bird_dog", "ציפור-כלב", Branch.CORE, "body", false, unit = "reps", alt = "dead_bug,plank,side_plank"),
        ex("ab_wheel", "גלגל בטן", Branch.CORE, "body", false, unit = "reps", alt = "plank,cable_crunch,hanging_leg_raise"),
        ex("russian_twist", "סיבובי גו", Branch.CORE, "body", false, unit = "reps", alt = "bicycle_crunch,side_plank,dead_bug"),
        ex("bicycle_crunch", "כפיפת בטן אופניים", Branch.CORE, "body", false, unit = "reps", alt = "russian_twist,lying_leg_raise,dead_bug"),
        ex("mountain_climbers", "מטפסי הרים", Branch.CORE, "body", false, unit = "reps", alt = "plank,bicycle_crunch,dead_bug")
    )

    // ---------------------------------------------------------------- BANDS
    /**
     * Resistance-band versions for home / "גומייה" days (unit "reps": a band has no kilos). They join the coach's
     * slots only when the equipment is restricted (mode "bodyweight" or a note "גומייה / משקל גוף / בבית"), so the
     * regular gym rotation never changes because of them.
     */
    private val bands = listOf(
        ex("band_chest_press", "לחיצת חזה עם גומייה", Branch.CHEST, "band", true, unit = "reps", alt = "pushup,db_bench_press"),
        ex("band_row", "חתירה עם גומייה", Branch.BACK, "band", true, unit = "reps", alt = "inverted_row,db_row,cable_row"),
        ex("band_lat_pulldown", "משיכה מלמעלה עם גומייה", Branch.BACK, "band", true, unit = "reps", alt = "lat_pulldown,assisted_pullup,pullup"),
        ex("band_shoulder_press", "לחיצת כתפיים עם גומייה", Branch.SHOULDERS_ARMS, "band", true, unit = "reps", alt = "pike_pushup,shoulder_press"),
        ex("band_lateral_raise", "הרחקת כתפיים עם גומייה", Branch.SHOULDERS_ARMS, "band", false, unit = "reps", alt = "lateral_raise,cable_lateral_raise"),
        ex("band_curl", "כפיפת מרפקים עם גומייה", Branch.SHOULDERS_ARMS, "band", false, unit = "reps", alt = "db_curl,cable_curl"),
        ex("band_tricep_extension", "פשיטת מרפקים עם גומייה", Branch.SHOULDERS_ARMS, "band", false, unit = "reps", alt = "tricep_pushdown,bench_dips"),
        ex("band_squat", "סקוואט עם גומייה", Branch.LEGS, "band", true, unit = "reps", alt = "bodyweight_squat,goblet_squat"),
        ex("band_rdl", "דדליפט רומני עם גומייה", Branch.LEGS, "band", true, unit = "reps", alt = "glute_bridge,db_romanian_deadlift"),
        ex("band_hip_abduction", "פיסוק ירכיים עם גומייה", Branch.LEGS, "band", false, unit = "reps", alt = "glute_bridge,hip_abduction")
    )

    // ----------------------------------------------------------------- HOME
    /**
     * "אימון בבית" (HomeGear): versions for the gear people have at home. Like the bands they join the coach's
     * slots only on a home plan (the sheet, or a note "בבית"), so the gym rotation never changes because of them.
     * Equipment "kettlebell" / "trx" is gear of the same name; the others are body weight plus an extra piece
     * (bench, bar, ball, rope) listed in HomeGear. Body-weight compounds progress by reps, not kilos.
     */
    private val home = listOf(
        ex("db_floor_press", "לחיצת רצפה עם משקולות", Branch.CHEST, "dumbbell", true, alt = "db_bench_press,pushup"),
        ex("handles_pushup", "שכיבות סמיכה על ידיות", Branch.CHEST, "body", true, unit = "reps", alt = "pushup,decline_pushup"),
        ex("chin_up", "מתח באחיזה הפוכה", Branch.BACK, "body", true, unit = "reps", alt = "pullup,band_lat_pulldown"),
        ex("table_row", "חתירה מתחת לשולחן", Branch.BACK, "body", true, unit = "reps", alt = "band_row,db_row"),
        ex("trx_row", "חתירה ברצועות", Branch.BACK, "trx", true, unit = "reps", alt = "band_row,table_row"),
        ex("kb_row", "חתירה עם קטלבל", Branch.BACK, "kettlebell", true, alt = "db_row,band_row"),
        ex("ball_back_extension", "פשיטת גב על כדור", Branch.BACK, "body", false, unit = "reps", alt = "bird_dog,glute_bridge"),
        ex("kb_goblet_squat", "סקוואט גביע עם קטלבל", Branch.LEGS, "kettlebell", true, alt = "goblet_squat,bodyweight_squat"),
        ex("kb_swing", "תנופות קטלבל", Branch.LEGS, "kettlebell", true, unit = "reps", alt = "db_romanian_deadlift,glute_bridge"),
        ex("bw_bulgarian_split_squat", "סקוואט בולגרי במשקל גוף", Branch.LEGS, "body", true, unit = "reps", alt = "bulgarian_split_squat,reverse_lunge"),
        ex("reverse_lunge", "מכרעים לאחור במשקל גוף", Branch.LEGS, "body", true, unit = "reps", alt = "lunges,bodyweight_squat"),
        ex("step_up", "עליות על ספסל", Branch.LEGS, "body", true, unit = "reps", alt = "reverse_lunge,lunges"),
        ex("ball_hamstring_curl", "כפיפת ברכיים על כדור", Branch.LEGS, "body", false, unit = "reps", alt = "leg_curl,glute_bridge"),
        ex("kb_press", "לחיצת כתפיים עם קטלבל", Branch.SHOULDERS_ARMS, "kettlebell", true, alt = "shoulder_press,pike_pushup"),
        ex("prone_t_raise", "פתיחת ידיים בשכיבה על הבטן", Branch.SHOULDERS_ARMS, "body", false, unit = "reps", alt = "reverse_fly,band_pull_apart"),
        ex("trx_rear_fly", "פרפר הפוך ברצועות", Branch.SHOULDERS_ARMS, "trx", false, unit = "reps", alt = "reverse_fly,band_pull_apart"),
        ex("trx_curl", "כפיפת מרפקים ברצועות", Branch.SHOULDERS_ARMS, "trx", false, unit = "reps", alt = "db_curl,band_curl"),
        ex("diamond_pushup", "שכיבות סמיכה צרות", Branch.SHOULDERS_ARMS, "body", false, unit = "reps", alt = "bench_dips,band_tricep_extension"),
        ex("ball_crunch", "כפיפת בטן על כדור", Branch.CORE, "body", false, unit = "reps", alt = "bicycle_crunch,dead_bug"),
        ex("jump_rope", "קפיצה בחבל", Branch.ENDURANCE, "body", false, unit = "sec", alt = "jumping_jacks,high_knees")
    )

    // ----------------------------------------------------- ENDURANCE / CARDIO
    private val endurance = listOf(
        ex("walk", "הליכה", Branch.ENDURANCE, "body", false, unit = "sec", alt = "jumping_jacks,high_knees"),
        // the running coach (domain/coach/RunPlan): one timed set per interval session
        ex("run_intervals", "ריצה בהפסקות", Branch.ENDURANCE, "body", false, unit = "sec"),
        ex("burpees", "ברפיז", Branch.ENDURANCE, "body", true, unit = "reps", alt = "jumping_jacks,high_knees"),
        ex("jumping_jacks", "קפיצות פיסוק", Branch.ENDURANCE, "body", false, unit = "reps", alt = "high_knees,burpees"),
        ex("high_knees", "ברכיים גבוהות", Branch.ENDURANCE, "body", false, unit = "reps", alt = "jumping_jacks,burpees")
    )

    // ------------------------------------------------------- YOGA / MOBILITY
    /** Holds; unit "sec". Branch ENDURANCE (whole body) or CORE (spine). */
    private val mobility = listOf(
        ex("cat_cow", "חתול-פרה", Branch.CORE, "body", false, unit = "sec", alt = "cobra,thoracic_rotation"),
        ex("cobra", "קוברה", Branch.CORE, "body", false, unit = "sec", alt = "cat_cow,thoracic_rotation"),
        ex("thoracic_rotation", "סיבוב גב עליון", Branch.CORE, "body", false, unit = "sec", alt = "cat_cow,cobra"),
        ex("child_pose", "תנוחת הילד", Branch.ENDURANCE, "body", false, unit = "sec", alt = "downward_dog,pigeon_pose"),
        ex("downward_dog", "כלב מביט מטה", Branch.ENDURANCE, "body", false, unit = "sec", alt = "child_pose,hamstring_stretch"),
        ex("hip_flexor_stretch", "מתיחת כופפי ירך", Branch.ENDURANCE, "body", false, unit = "sec", alt = "pigeon_pose,worlds_greatest_stretch"),
        ex("pigeon_pose", "תנוחת היונה", Branch.ENDURANCE, "body", false, unit = "sec", alt = "hip_flexor_stretch,hamstring_stretch"),
        ex("hamstring_stretch", "מתיחת ירך אחורית", Branch.ENDURANCE, "body", false, unit = "sec", alt = "downward_dog,pigeon_pose"),
        ex("worlds_greatest_stretch", "המתיחה הגדולה בעולם", Branch.ENDURANCE, "body", false, unit = "sec", alt = "hip_flexor_stretch,downward_dog")
    )

    private val mobilityIds: Set<String> = mobility.map { it.id }.toSet()

    /** A whole yoga class he did elsewhere and logs by hand ("הוספת יוגה"): one timed set. In no coach pool. */
    private val classes = listOf(
        ex("yoga_session", "שיעור יוגה", Branch.ENDURANCE, "body", false, unit = "sec")
    )

    val all: List<ExerciseEntity> = chest + back + legs + shouldersArms + core + bands + home + endurance + mobility + classes

    private val byIdMap: Map<String, ExerciseEntity> = all.associateBy { it.id }

    fun byId(id: String): ExerciseEntity? = byIdMap[id]

    /** Everything you do in a gym session (not holds / stretches / walking). */
    val gym: List<ExerciseEntity> = (chest + back + legs + shouldersArms + core + bands + home.filter { it.id != "jump_rope" })

    /** Yoga / mobility holds. */
    val yoga: List<ExerciseEntity> = mobility

    /** Short bodyweight work for a mini session. */
    val miniPool: List<ExerciseEntity> = (chest + legs + core + endurance).filter { it.equipment == "body" && it.id != "walk" && it.id != "run_intervals" }

    fun isMobility(id: String): Boolean = id in mobilityIds

    fun alternativeIds(id: String): List<String> =
        byId(id)?.alternativesCsv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && byIdMap.containsKey(it) }
}
