import os
import shutil
import re

def read_base_file(filepath):
    """Read base file and extract all sections."""
    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    # Header: first 7 lines (J, J_0, I, S, K_l, K_f, N)
    header = [l.rstrip('\n') for l in lines[0:7]]
    
    # Find all sections dynamically
    sections = {}
    current_section = None
    section_buffer = []
    
    for i, line in enumerate(lines):
        stripped = line.rstrip('\n')
        if stripped == '':
            if current_section and section_buffer:
                sections[current_section] = section_buffer
            current_section = None
            section_buffer = []
            continue
        
        if stripped.startswith('J-ID'):
            if current_section and section_buffer:
                sections[current_section] = section_buffer
            current_section = 'j_header'
            section_buffer = [line]
        elif stripped.startswith('J_0-ID'):
            if current_section and section_buffer:
                sections[current_section] = section_buffer
            current_section = 'j0_header'
            section_buffer = [line]
        elif stripped.startswith('I-ID'):
            if current_section and section_buffer:
                sections[current_section] = section_buffer
            current_section = 'i_header'
            section_buffer = [line]
        elif stripped.startswith('s\t'):
            if current_section and section_buffer:
                sections[current_section] = section_buffer
            current_section = 'service_header'
            section_buffer = [line]
        elif stripped.startswith('B\t'):
            if current_section and section_buffer:
                sections[current_section] = section_buffer
            current_section = 'params'
            section_buffer = [line]
        elif current_section:
            section_buffer.append(line)
        # Lines before first section (already handled by header)
    
    if current_section and section_buffer:
        sections[current_section] = section_buffer
    
    return header, sections

def generate_data_file(header, sections, target_i, target_j, output_path):
    """Generate a data file with target I and J from base sections."""
    # Update header
    new_header = header.copy()
    new_header[0] = f"J\t{target_j}"
    new_header[2] = f"I\t{target_i}"
    
    with open(output_path, 'w', encoding='utf-8') as f:
        # Write header
        for line in new_header:
            f.write(line + '\n')
        f.write('\n')
        
        # Write J header and first target_j J nodes
        j_section = sections.get('j_header', [])
        if j_section:
            f.write(j_section[0])  # header line
            for line in j_section[1:target_j + 1]:
                f.write(line)
        f.write('\n')
        
        # Write J_0 header and all J_0 nodes (fixed 5)
        j0_section = sections.get('j0_header', [])
        if j0_section:
            f.write(j0_section[0])  # header line
            for line in j0_section[1:]:
                f.write(line)
        f.write('\n')
        
        # Write I header and first target_i I nodes (re-index IDs from 0)
        i_section = sections.get('i_header', [])
        if i_section:
            f.write(i_section[0])  # header line
            selected_i_nodes = i_section[1:target_i + 1]
            for idx, line in enumerate(selected_i_nodes):
                parts = line.rstrip('\n').split('\t')
                if len(parts) > 0:
                    parts[0] = str(idx)  # renumber ID from 0
                    f.write('\t'.join(parts) + '\n')
        f.write('\n')
        
        # Write service table (unchanged)
        service_section = sections.get('service_header', [])
        for line in service_section:
            f.write(line)
        f.write('\n')
        
        # Write params (unchanged)
        param_section = sections.get('params', [])
        for line in param_section:
            f.write(line)

def main():
    base_file = 'alldata/200-50.txt'
    output_dir = 'alldata'
    
    # New combinations: I × J
    i_values = [50, 75, 100, 125, 150, 175, 200]
    j_values = [10,  20,  30,  40,  50]
    
    target_combinations = set()
    for i_val in i_values:
        for j_val in j_values:
            target_combinations.add((i_val, j_val))
    
    # Read base file
    header, sections = read_base_file(base_file)
    
    # Verify base data sizes
    j_nodes = sections.get('j_header', [])[1:]
    i_nodes = sections.get('i_header', [])[1:]
    print(f"Base file: {len(j_nodes)} J nodes, {len(i_nodes)} I nodes")
    
    if len(j_nodes) < max(j_values):
        print(f"ERROR: Base file only has {len(j_nodes)} J nodes, need {max(j_values)}")
        return
    if len(i_nodes) < max(i_values):
        print(f"ERROR: Base file only has {len(i_nodes)} I nodes, need {max(i_values)}")
        return
    
    # Backup existing files
    backup_dir = 'alldata_backup'
    if os.path.exists(backup_dir):
        shutil.rmtree(backup_dir)
    os.makedirs(backup_dir, exist_ok=True)
    for f in os.listdir(output_dir):
        if f.endswith('.txt'):
            shutil.copy2(os.path.join(output_dir, f), os.path.join(backup_dir, f))
    print(f"Backed up existing files to {backup_dir}")
    
    # Build mapping of existing files (both J-I and I-J formats)
    existing_files = {}
    for f in os.listdir(output_dir):
        if not f.endswith('.txt'):
            continue
        # Parse filename: could be J-I or I-J
        m = re.match(r'(\d+)-(\d+)\.txt$', f)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            # Determine if it's J-I or I-J based on which values are in our sets
            if a in j_values and b in i_values:
                # Likely J-I format
                existing_files[(b, a)] = f  # Map to (I, J) -> filename
            elif a in i_values and b in j_values:
                # Likely I-J format
                existing_files[(a, b)] = f
            else:
                # Unknown, store both possibilities
                existing_files[(a, b)] = f
    
    # Remove all existing .txt files in alldata
    for f in os.listdir(output_dir):
        if f.endswith('.txt'):
            os.remove(os.path.join(output_dir, f))
    print("Removed old files")
    
    # Generate all target files
    generated = 0
    reused = 0
    for i_val in i_values:
        for j_val in j_values:
            new_filename = f"{i_val}-{j_val}.txt"
            output_path = os.path.join(output_dir, new_filename)
            
            # Check if we have an existing file for this combination
            existing_old_name = None
            # Try J-I format first
            if (i_val, j_val) in existing_files:
                existing_old_name = existing_files[(i_val, j_val)]
            
            if existing_old_name and os.path.exists(os.path.join(backup_dir, existing_old_name)):
                # Reuse existing file content, just rename
                old_path = os.path.join(backup_dir, existing_old_name)
                # But we need to fix the header to match I-J naming (the content already has correct I/J)
                # Actually existing files already have correct I and J values in content
                # We just need to ensure the header is consistent
                shutil.copy2(old_path, output_path)
                reused += 1
                print(f"Reused: {existing_old_name} -> {new_filename}")
            else:
                # Generate new file from base
                generate_data_file(header, sections, i_val, j_val, output_path)
                generated += 1
                print(f"Generated: {new_filename}")
    
    print(f"\nTotal: {reused} reused, {generated} newly generated = {reused + generated} files")
    print(f"All files use naming format: I-J.txt")
    
    # Clean up backup
    shutil.rmtree(backup_dir)
    print("Cleaned up backup directory")

if __name__ == '__main__':
    main()
