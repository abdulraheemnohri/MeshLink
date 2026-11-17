# Room schema snapshots

Room's KSP processor writes versioned database schema JSON files into this directory.

- Database class: `com.example.data.database.AppDatabase`
- Current schema version: `8`
- Expected generated snapshot: `com.example.data.database.AppDatabase/8.json`

Do not hand-author or edit generated schema JSON. Generate it with the Android Gradle build, review the JSON alongside every database entity/DAO/migration change, and commit the generated snapshot. CI also uploads the generated schema directory as a build artifact so a missing committed snapshot is visible during migration review.

The earliest database schema present in repository history is version 3. Migrations for versions 1 and 2 must not be invented without their actual historical schema.
