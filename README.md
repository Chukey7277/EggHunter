# Virtual Tour AR App

## Project Overview
This Android application demonstrates the use of ARCore's Geospatial API to create an augmented reality (AR) experience where users can explore real-world locations, discover and collect virtual easter eggs, and learn fun facts about each location. The app is designed for educational and touristic purposes, leveraging geospatial anchors to tie virtual content to real-world coordinates.

## Features
- **Geospatial Anchors:** Place and render virtual objects at real-world GPS locations using ARCore's Geospatial API.
- **Collectible Eggs:** Users can find and collect virtual eggs at predefined campus landmarks.
- **Fun Facts:** Each anchor/location provides interesting information or trivia.
- **ARCore Integration:** Utilizes ARCore for world tracking, rendering, and geospatial localization.
- **Progress Tracking:** Keeps track of collected anchors/eggs.
- **Interactive UI:** Dialogs, status messages, and feedback for user actions.

## Prerequisites
- **Android Studio** (latest stable version recommended)
- **Android device** with:
  - ARCore support ([see supported devices](https://developers.google.com/ar/devices))
  - Google Play Services for AR installed
  - Android 7.0 (Nougat) or higher
  - api key is not needed (the app is keyless, but the OAuth ID needs to be set, the process is explained below)

## Setup Instructions
1. **Clone the Repository:**
   ```bash
   git clone <your-repo-url>
   cd virtual-tour-ar
   ```
2. **Open in Android Studio:**
   - Open Android Studio.
   - Select `Open an existing project` and choose this directory.
4. **Configure Google Services:**
   - this app is keyless, so follow the steps in this link for keyless authorization
   - [steps for enabling and using arcore](https://developers.google.com/ar/develop/authorization?platform=android)
5. **Build the Project:**
   - When the project is opened in the android studio, it takes some time for the gradle to sync. It is done automatically.
   - Let Gradle sync and resolve dependencies
   - To build the app go to file -> sync project with gradle files
7. **Run the App:**
   - Connect your ARCore-supported Android device via WiFi
   - Select your device and run the app

## Usage Workflow
1. **Permissions:**
   - On first launch, grant camera and location permissions when prompted
2. **Geospatial Access:**
   - Accept the privacy notice to enable geospatial features
3. **Exploring:**
   - Move around the campus with your device
   - The app will localize your position and display virtual eggs at specific landmarks
4. **Collecting Anchors:**
   - Approach a virtual egg and tap on it to collect
   - Read the fun fact associated with each anchor
5. **Viewing Progress:**
   - Click the Home button in the screen to go back to the Homeactivity
   - Click on the View Collectibles button to see all the collected cards, by using the next button

## Code Organization and Section Guide
The `GeospatialActivity.java` file is organized into clearly marked sections for maintainability and clarity. Here's what each section contains:


**1. Package and Imports**
- The package name and the imports are defined

**2. Class Declaration**
- The start of the class, including the class name, inheritance, and implemented interfaces.
- Example: `public class GeospatialActivity extends AppCompatActivity implements SampleRender.Renderer, PrivacyNoticeDialogFragment.NoticeDialogListener`

**3. All Static Constants Grouped Together**
- All `private static final` constants used throughout the class, such as tag strings, keys, thresholds, and configuration values.
- Example: `private static final String TAG = GeospatialActivity.class.getSimpleName();`

**4. Nested Enums and Classes**
- Any enums or inner classes used by the activity, such as the `State` enum for tracking app state and the `PredefinedAnchor` class for anchor data.
- Example: `enum State { UNINITIALIZED, ... }`, `public static class PredefinedAnchor { ... }`

**5. Instance Variables (Grouped by Access Modifier)**
- All instance fields, grouped by access modifier (e.g., all `private` fields together). Includes UI components, ARCore session objects, rendering helpers, state variables, and data structures.
- Example: `private GLSurfaceView surfaceView;`, `private volatile Session session;`

**6. Lifecycle Methods**
- Android lifecycle methods that manage the activity's state, such as `onCreate`, `onResume`, `onPause`, and `onDestroy`.
- Example: `@Override protected void onCreate(Bundle savedInstanceState) { ... }`

**7. Permission & System Event Handlers**
- Methods that handle permission results and system events, such as `onRequestPermissionsResult` and `onWindowFocusChanged`.
- Example: `@Override public void onRequestPermissionsResult(...) { ... }`

**8. SampleRender.Renderer Interface Methods**
- Methods required by the AR rendering interface, including `onSurfaceCreated`, `onSurfaceChanged`, and `onDrawFrame`.
- Example: `@Override public void onDrawFrame(SampleRender render) { ... }`

**9. Dialog Interface Methods**
- Methods for handling dialog interactions, such as privacy notices.
- Example: `@Override public void onDialogPositiveClick(DialogFragment dialog) { ... }`

**10. Core AR Session Management**
- Methods for creating, configuring, and managing the ARCore session.
- Example: `public void createSession() { ... }`, `private void configureSession() { ... }`

**11. Geospatial State Management Methods**
- Methods for updating and tracking the geospatial localization state.
- Example: `public void updateGeospatialState(Earth earth) { ... }`, `private void updatePretrackingState(Earth earth) { ... }`

**12. Anchor Management Methods**
- Methods for creating and managing geospatial anchors in the AR scene.
- Example: `public void createTerrainAnchor(Earth earth, PredefinedAnchor pa, ...) { ... }`

**13. Input Handling Methods**
- Methods for handling user input, such as taps on the AR view.
- Example: `public void handleTap(Frame frame, TrackingState cameraTrackingState, ...) { ... }`

**14. UI & Dialog Methods**
- Methods for showing dialogs and updating the UI in response to events.
- Example: `public void showPrivacyNoticeDialog() { ... }`, `public void showCongratulationsDialog() { ... }`

**15. Utility & Helper Methods**
- General-purpose helper methods used throughout the activity.
- Example: `public double calculateGPSDistance(...)`, `public boolean checkSensorAvailability() { ... }`

**16. Static Utility Methods**
- Static methods that can be called without an instance, often for data management or utility purposes.
- Example: `public static void updateCollectedStatusFromPrefs(Context context) { ... }`

## Project Structure
- `app/src/main/java/com/example/virtualtourar/`
  - **GeospatialActivity.java:** Main AR activity, geospatial logic, anchor management.
  - **CollectiblesActivity.java:** Displays collected anchors.
  - **HomeActivity.java:** App entry point/home screen.
  - **helpers/**: Permission and UI helpers.
  - **samplerender/**: Rendering utilities and ARCore integration.
- `app/src/main/assets/models/`: 3D models and textures for virtual objects.
- `app/src/main/assets/shaders/`: GLSL shaders for rendering.
- `app/src/main/res/`: Layouts, drawables, strings, and other resources.
- `google-services.json`: Google API configuration (not included in repo)

## Customization
- **Add New Anchors:**
  - Edit the `predefinedAnchors` list in `GeospatialActivity.java`.
  - Add new `PredefinedAnchor` entries with name, coordinates, label, fun fact, and model/texture.
- **Add Models/Textures:**
  - Place new `.obj` and texture files in `app/src/main/assets/models/`.
  - Reference them in the anchor definition.

## Troubleshooting
- **App crashes on launch:**
  - Ensure your device supports ARCore and has Google Play Services for AR installed.
  - Check that all permissions are granted.
- **Geospatial features not working:**
  - Make sure you have a stable internet connection and clear view of the sky.
  - Verify your `google-services.json` is correct.
- **Build errors:**
  - Sync Gradle and ensure all dependencies are resolved.
  - Clean and rebuild the project if needed.

## License
This project is provided for educational purposes
