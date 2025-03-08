package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.FirebaseFirestore;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;


public class MainActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private EditText emailEditText, passwordEditText;
    private Button loginButton;
    private LinearLayout selectionLayout;
    private Button wardenButton, advisorButton, securityButton;
    private String selectedEmail;
    private Button forgetPasswordButton;
    private FirebaseAuth auth;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Firebase Firestore and UI components
        db = FirebaseFirestore.getInstance();
        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginButton = findViewById(R.id.loginButton);
        selectionLayout = findViewById(R.id.selectionLayout);
        wardenButton = findViewById(R.id.wardenButton);
        advisorButton = findViewById(R.id.advisorButton);
        securityButton = findViewById(R.id.securityButton);

        // Set Login Button Click Listener
        loginButton.setOnClickListener(v -> handleLogin());

        // Ensure buttons navigate to their respective activities
        wardenButton.setOnClickListener(v -> navigateToActivity("warden", selectedEmail));
        advisorButton.setOnClickListener(v -> navigateToActivity("advisor", selectedEmail));
        securityButton.setOnClickListener(v -> navigateToActivity("security", selectedEmail));

        auth = FirebaseAuth.getInstance();
        migrateUsersToFirebaseAuth();

        forgetPasswordButton = findViewById(R.id.forgetPasswordButton);
        forgetPasswordButton.setOnClickListener(v -> showForgotPasswordDialog());
    }

    private void showForgotPasswordDialog() {
        EditText emailInput = new EditText(this);
        emailInput.setHint("Enter your email");

        new android.app.AlertDialog.Builder(this)
                .setTitle("Reset Password")
                .setMessage("Enter your email to receive a password reset link")
                .setView(emailInput)
                .setPositiveButton("Send", (dialog, which) -> {
                    String email = emailInput.getText().toString().trim();
                    if (!email.isEmpty()) {
                        sendPasswordResetEmail(email);
                    } else {
                        showToast("Please enter a valid email");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendPasswordResetEmail(String email) {
        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> showToast("Reset link sent to your email"))
                .addOnFailureListener(e -> showToast("Error: " + e.getMessage()));
    }


    private void migrateUsersToFirebaseAuth() {
        db.collection("advisors")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        Log.d("Migration", "No users found in Firestore.");
                        return;
                    }

                    for (DocumentSnapshot document : queryDocumentSnapshots) {
                        String email = document.getString("email");
                        String password = document.getString("password");

                        if (email != null) {
                            if (password == null || password.length() < 6) { // Check for weak or missing password
                                password = "Default@123"; // Assign a strong default password
                            }

                            String finalPassword = password; // Need final variable for lambda expressions
                            auth.createUserWithEmailAndPassword(email, finalPassword)
                                    .addOnSuccessListener(authResult -> {
                                        Log.d("Migration", "User added: " + email);
                                        removePasswordFromFirestore(document.getId());
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e("Migration", "Failed to add user: " + email, e);

                                        // Handle known errors
                                        if (e.getMessage().contains("already in use")) {
                                            Log.w("Migration", "Email already exists: " + email);
                                        } else {
                                            Log.w("Migration", "Error adding user: " + email);
                                        }
                                    });
                        } else {
                            Log.w("Migration", "Skipping user with missing email.");
                        }
                    }

                    Toast.makeText(this, "User migration completed with logs!", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> Log.e("Migration", "Failed to fetch users", e));
    }



    private void removePasswordFromFirestore(String userId) {
        db.collection("studentemail").document(userId)
                .update("password", null)
                .addOnSuccessListener(aVoid -> Log.d("Cleanup", "Password removed for: " + userId))
                .addOnFailureListener(e -> Log.e("Cleanup", "Failed to remove password", e));
    }

    private void handleLogin() {
        String email = emailEditText.getText().toString().trim().toLowerCase();
        String password = passwordEditText.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            showToast("Please enter email and password");
            return;
        }

        if (!email.endsWith("@kongu.edu")) {
            showToast("Invalid email domain. Please use @kongu.edu");
            return;
        }

        checkUserRole(email, password);
    }

    private void checkUserRole(String email, String password) {
        db.collection("security")
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener(securitySnapshot -> {
                    if (!securitySnapshot.isEmpty()) {
                        navigateToActivity("security", email);
                    } else {
                        checkWardenAndAdvisor(email, password);
                    }
                })
                .addOnFailureListener(e -> logError("Security role check failed", e));
    }

    private void checkWardenAndAdvisor(String email, String password) {
        db.collection("warden")
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener(wardenSnapshot -> {
                    boolean isWarden = !wardenSnapshot.isEmpty();

                    db.collection("advisors")
                            .whereEqualTo("email", email)
                            .get()
                            .addOnSuccessListener(advisorSnapshot -> {
                                boolean isAdvisor = !advisorSnapshot.isEmpty();

                                if (isWarden && isAdvisor) {
                                    validateMultiRole(email, password);
                                } else if (isWarden) {
                                    validateRole(email, password, "warden");
                                } else if (isAdvisor) {
                                    validateRole(email, password, "advisor");
                                } else {
                                    checkStudentCredentials(email, password);
                                }
                            })
                            .addOnFailureListener(e -> logError("Advisor role check failed", e));
                })
                .addOnFailureListener(e -> logError("Warden role check failed", e));
    }

    private void validateMultiRole(String email, String password) {
        db.collection("warden")
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener(wardenSnapshot -> {
                    boolean isWardenValid = !wardenSnapshot.isEmpty();

                    db.collection("advisors")
                            .whereEqualTo("email", email)
                            .whereEqualTo("password", password)
                            .get()
                            .addOnSuccessListener(advisorSnapshot -> {
                                boolean isAdvisorValid = !advisorSnapshot.isEmpty();

                                if (isWardenValid && isAdvisorValid) {
                                    showSelectionLayout(email);
                                } else if (isWardenValid) {
                                    navigateToActivity("warden", email);
                                } else if (isAdvisorValid) {
                                    navigateToActivity("advisor", email);
                                } else {
                                    showToast("Invalid password");
                                }
                            });
                });
    }

    private void validateRole(String email, String password, String role) {
        db.collection(role)
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.isEmpty()) {
                        navigateToActivity(role, email);
                    } else {
                        showToast("Invalid password");
                    }
                })
                .addOnFailureListener(e -> logError(role + " role validation failed", e));
    }

    private void checkStudentCredentials(String email, String password) {
        db.collection("studentemail")
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener(studentSnapshot -> {
                    if (!studentSnapshot.isEmpty()) {
                        checkUserInfo(email);
                    } else {
                        showToast("Invalid email or password");
                    }
                })
                .addOnFailureListener(e -> logError("Student credentials check failed", e));
    }

    private void checkUserInfo(String email) {
        db.collection("users")
                .document(email)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        navigateToActivity("display_user", email);
                    } else {
                        navigateToActivity("user_info", email);
                    }
                })
                .addOnFailureListener(e -> logError("User info check failed", e));
    }

    private void showSelectionLayout(String email) {
        selectedEmail = email;
        selectionLayout.setVisibility(View.VISIBLE);
        loginButton.setVisibility(View.GONE);
        emailEditText.setVisibility(View.GONE);
        passwordEditText.setVisibility(View.GONE);

        // Make buttons visible
        wardenButton.setVisibility(View.VISIBLE);
        advisorButton.setVisibility(View.VISIBLE);
    }

    private void navigateToActivity(String role, String email) {
        Intent intent = null;
        switch (role) {
            case "warden":
                intent = new Intent(MainActivity.this, HostelInfoActivity.class);
                intent.putExtra("WARDEN_EMAIL", email);
                break;
            case "advisor":
                intent = new Intent(MainActivity.this, AdvisorInfoActivity.class);
                intent.putExtra("EMAIL", email);
                break;
            case "security":
                intent = new Intent(MainActivity.this, SecurityDashboardActivity.class);
                intent.putExtra("EMAIL", email);
                break;
            case "user_info":
                intent = new Intent(MainActivity.this, Userinfo.class);
                intent.putExtra("EMAIL", email);
                break;
            case "display_user":
                intent = new Intent(MainActivity.this, DisplayUserInfoActivity.class);
                intent.putExtra("EMAIL", email);
                break;
        }
        if (intent != null) {
            startActivity(intent);
        } else {
            showToast("Navigation error");
        }
    }

    private void logError(String message, Exception e) {
        showToast(message + ": " + e.getMessage());
        Log.e("MainActivity", message, e);
    }

    private void showToast(String message) {
        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
    }
}