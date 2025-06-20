package org.example.kqz.services.impls;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.example.kqz.exceptions.ResourceNotFoundException;
import org.example.kqz.repositories.UserRepository;
import org.example.kqz.security.AppUserDetails;
import org.example.kqz.services.interfaces.AuthService;
import org.example.kqz.services.interfaces.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuthServiceImplementation implements AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<Object, Boolean> emailSent = ConcurrentHashMap.newKeySet(); // to avoid multiple emails


    // qe me marre ni value prej application.properties - qysh e kem shenu atje, duhet edhe ktu
    @Value("${jwt.secret}")
    private String secretKey;
    private final Long expirationTime = 86400000L;

    @Override
    public UserDetails authenticate(String email, String password) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));

            failedAttempts.remove(email);
            emailSent.remove(email);

            return userDetailsService.loadUserByUsername(email);

        } catch (Exception ex) {
            failedAttempts.merge(email, 1, Integer::sum);

            if (failedAttempts.get(email) >= 3 && !emailSent.contains(email)) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    emailService.sendLoginAlert(
                            user.getEmail(),
                            user.getFirstName() + " " + user.getLastName()
                    );
                    emailSent.add(email);
                });
            }

            throw ex;
        }
    }


    @Override
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();

        claims.put("authorities", userDetails.getAuthorities());

        claims.put("id", ((AppUserDetails) userDetails).getUser().getId());
        claims.put("role", ((AppUserDetails) userDetails).getUser().getRole());
        claims.put("personalNo", ((AppUserDetails) userDetails).getUser().getPersonalNo());
        claims.put("firstName", ((AppUserDetails) userDetails).getUser().getFirstName());
        claims.put("lastName", ((AppUserDetails) userDetails).getUser().getLastName());
        claims.put("nationality", ((AppUserDetails) userDetails).getUser().getNationality());
        claims.put("hasVoted", ((AppUserDetails) userDetails).getUser().isHasVoted());

        // token builder pattern
        return Jwts.builder()
                .setClaims(claims) // ckado qe vendoset ne body te tokenit, quhen claims
                .setSubject(userDetails.getUsername()) // email ne rastin tone
                .setIssuedAt(new Date(System.currentTimeMillis())) // kur ka fillu
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime)) // kur ka mu ba expire tokeni
                .signWith(getSecretKey(), SignatureAlgorithm.HS256) // secret key me rujte me ni algoritem
                .compact();
    }

    private Key getSecretKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes());
    }

    @Override
    public UserDetails validateToken(String token) {
        String email = extractUsername(token);
        return userDetailsService.loadUserByUsername(email);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSecretKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
        // ne body qe na u kthy prej tokenit, po i thojme qe me marre veq sub, qe ne rastin tone osht email
    }


    public static Authentication getAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public static String getLoggedInUserEmail() {
        Authentication authentication = getAuthentication();
        return authentication.getName();
    }

    public static String getLoggedInUserRole() {
        Authentication authentication = getAuthentication();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
    }

}
